package com.luffy.tradeflow_portfolio_service;

import com.luffy.tradeflow.common.events.EventCodec;
import com.luffy.tradeflow.common.events.Events;
import com.luffy.tradeflow.common.events.Events.OrderSide;
import com.luffy.tradeflow.common.events.Events.OrderType;
import com.luffy.tradeflow.common.events.Topics;
import com.luffy.tradeflow.common.kafka.InconsistentStateException;
import com.luffy.tradeflow.common.outbox.OutboxEvent;
import com.luffy.tradeflow.common.outbox.OutboxEventRepository;
import com.luffy.tradeflow.common.testsupport.TestJwts;
import com.luffy.tradeflow_portfolio_service.client.MarketDataClient;
import com.luffy.tradeflow_portfolio_service.domain.Reservation;
import com.luffy.tradeflow_portfolio_service.messaging.PortfolioEventListener;
import com.luffy.tradeflow_portfolio_service.repository.AccountRepository;
import com.luffy.tradeflow_portfolio_service.repository.HoldingRepository;
import com.luffy.tradeflow_portfolio_service.repository.LedgerEntryRepository;
import com.luffy.tradeflow_portfolio_service.repository.ReservationRepository;
import com.luffy.tradeflow_portfolio_service.service.ReconciliationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The portfolio side of the saga against H2: the listener is called with encoded
 * events exactly as Kafka would deliver them (including redeliveries).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AccountingIntegrationTest {

    private static final AtomicLong IDS = new AtomicLong(1000);

    @Autowired PortfolioEventListener listener;
    @Autowired EventCodec codec;
    @Autowired AccountRepository accounts;
    @Autowired HoldingRepository holdings;
    @Autowired ReservationRepository reservations;
    @Autowired LedgerEntryRepository ledger;
    @Autowired OutboxEventRepository outbox;
    @Autowired ReconciliationService reconciliation;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @MockitoBean MarketDataClient marketData;

    private long user;

    @BeforeEach
    void openAccount() {
        user = IDS.incrementAndGet();
        listener.onUserRegistered(encode(Topics.USERS_REGISTERED, new Events.UserRegistered(user, "u@test.dev", "U")));
        when(marketData.quotes(anyCollection())).thenReturn(List.of());
    }

    private String encode(String topic, Object payload) {
        return codec.encode(codec.wrap(topic, String.valueOf(user), payload));
    }

    private UUID placeAndReserve(OrderSide side, String symbol, long qty, String price) {
        UUID orderId = UUID.randomUUID();
        listener.onOrderPlaced(encode(Topics.ORDERS_PLACED, new Events.OrderPlaced(orderId, user, symbol, side,
                OrderType.MARKET, qty, new BigDecimal(price))));
        return orderId;
    }

    private Events.TradeExecuted trade(UUID orderId, OrderSide side, String symbol, long qty, String price) {
        BigDecimal p = new BigDecimal(price);
        return new Events.TradeExecuted(UUID.randomUUID(), orderId, user, symbol, side, qty, p,
                p.multiply(BigDecimal.valueOf(qty)).setScale(2), Instant.now());
    }

    private List<OutboxEvent> outboxFor(UUID orderId) {
        return outbox.findAll().stream().filter(e -> e.getMessageKey().equals(orderId.toString())).toList();
    }

    @Test
    void registrationOpensAccountOnceEvenWhenRedelivered() {
        String event = encode(Topics.USERS_REGISTERED, new Events.UserRegistered(user, "u@test.dev", "U"));
        listener.onUserRegistered(event);
        listener.onUserRegistered(event);
        assertThat(accounts.findById(user)).get().satisfies(a ->
                assertThat(a.getCashBalance()).isEqualByComparingTo("1000000.00"));
        assertThat(ledger.findByUserIdOrderByOccurredAtAscIdAsc(user)).hasSize(1);
    }

    @Test
    void buyIsReservedThenSettledWithExactCash() {
        UUID orderId = placeAndReserve(OrderSide.BUY, "TCS", 10, "250.00");
        assertThat(accounts.findById(user).orElseThrow().getReservedCash()).isEqualByComparingTo("2500.00");
        assertThat(outboxFor(orderId)).extracting(OutboxEvent::getTopic).containsExactly(Topics.ORDERS_RESERVED);

        listener.onTradeExecuted(encode(Topics.TRADES_EXECUTED, trade(orderId, OrderSide.BUY, "TCS", 10, "250.00")));

        var account = accounts.findById(user).orElseThrow();
        assertThat(account.getCashBalance()).isEqualByComparingTo("997500.00");
        assertThat(account.getReservedCash()).isEqualByComparingTo("0.00");
        assertThat(holdings.findByUserIdAndSymbol(user, "TCS").orElseThrow().getQuantity()).isEqualTo(10);
        assertThat(reservations.findById(orderId).orElseThrow().getStatus()).isEqualTo(Reservation.Status.SETTLED);
        assertThat(reconciliation.check(user).consistent()).isTrue();
    }

    @Test
    void insufficientFundsAndPositionAreRejectedWithoutSideEffects() {
        UUID tooBig = placeAndReserve(OrderSide.BUY, "MARUTI", 1000, "12500.00");
        UUID noShares = placeAndReserve(OrderSide.SELL, "INFY", 1, "100.00");

        assertThat(outboxFor(tooBig)).singleElement().satisfies(e -> {
            assertThat(e.getTopic()).isEqualTo(Topics.ORDERS_REJECTED);
            assertThat(e.getPayload()).contains("INSUFFICIENT_FUNDS");
        });
        assertThat(outboxFor(noShares)).singleElement()
                .satisfies(e -> assertThat(e.getPayload()).contains("INSUFFICIENT_POSITION"));
        assertThat(accounts.findById(user).orElseThrow().getReservedCash()).isEqualByComparingTo("0");
        assertThat(reservations.findById(tooBig)).isEmpty();
    }

    @Test
    void duplicateTradeEventsNeverDoubleCount() {
        UUID orderId = placeAndReserve(OrderSide.BUY, "TCS", 4, "100.00");
        var trade = trade(orderId, OrderSide.BUY, "TCS", 4, "100.00");
        String event = encode(Topics.TRADES_EXECUTED, trade);

        listener.onTradeExecuted(event);                                         // first delivery
        listener.onTradeExecuted(event);                                         // Kafka redelivery (same eventId)
        listener.onTradeExecuted(encode(Topics.TRADES_EXECUTED, trade));         // re-published (new eventId, same tradeId)

        assertThat(accounts.findById(user).orElseThrow().getCashBalance()).isEqualByComparingTo("999600.00");
        assertThat(holdings.findByUserIdAndSymbol(user, "TCS").orElseThrow().getQuantity()).isEqualTo(4);
        assertThat(ledger.findByUserIdOrderByOccurredAtAscIdAsc(user)).hasSize(2); // deposit + buy
    }

    @Test
    void sellRealizesProfitAndCancelReleasesShares() {
        UUID buy = placeAndReserve(OrderSide.BUY, "INFY", 10, "100.00");
        listener.onTradeExecuted(encode(Topics.TRADES_EXECUTED, trade(buy, OrderSide.BUY, "INFY", 10, "100.00")));

        UUID sell = placeAndReserve(OrderSide.SELL, "INFY", 4, "130.00");
        assertThat(holdings.findByUserIdAndSymbol(user, "INFY").orElseThrow().getReservedQuantity()).isEqualTo(4);
        listener.onTradeExecuted(encode(Topics.TRADES_EXECUTED, trade(sell, OrderSide.SELL, "INFY", 4, "130.00")));

        var account = accounts.findById(user).orElseThrow();
        assertThat(account.getRealizedPnl()).isEqualByComparingTo("120.00");     // (130 − 100) × 4
        assertThat(account.getCashBalance()).isEqualByComparingTo("999520.00");  // −1000 + 520

        UUID resting = placeAndReserve(OrderSide.SELL, "INFY", 6, "200.00");
        assertThat(holdings.findByUserIdAndSymbol(user, "INFY").orElseThrow().availableQuantity()).isZero();
        String cancel = encode(Topics.ORDERS_CANCELLED, new Events.OrderCancelled(resting, user, "user"));
        listener.onOrderCancelled(cancel);
        listener.onOrderCancelled(encode(Topics.ORDERS_CANCELLED, new Events.OrderCancelled(resting, user, "again")));
        assertThat(holdings.findByUserIdAndSymbol(user, "INFY").orElseThrow().getReservedQuantity()).isZero();
        assertThat(reservations.findById(resting).orElseThrow().getStatus()).isEqualTo(Reservation.Status.RELEASED);
        assertThat(reconciliation.check(user).consistent()).isTrue();
    }

    @Test
    void tradesThatContradictReservationsAreRejectedNotBooked() {
        UUID unknownOrder = UUID.randomUUID();
        assertThatThrownBy(() -> listener.onTradeExecuted(encode(Topics.TRADES_EXECUTED,
                trade(unknownOrder, OrderSide.BUY, "TCS", 1, "10.00"))))
                .isInstanceOf(InconsistentStateException.class);

        UUID orderId = placeAndReserve(OrderSide.BUY, "TCS", 2, "100.00");
        assertThatThrownBy(() -> listener.onTradeExecuted(encode(Topics.TRADES_EXECUTED,
                trade(orderId, OrderSide.BUY, "TCS", 3, "100.00"))))
                .isInstanceOf(InconsistentStateException.class).hasMessageContaining("quantity");
        assertThatThrownBy(() -> listener.onTradeExecuted(encode(Topics.TRADES_EXECUTED,
                trade(orderId, OrderSide.BUY, "TCS", 2, "100.01"))))
                .isInstanceOf(InconsistentStateException.class).hasMessageContaining("exceeds reservation");
        // Nothing was booked: the failed transactions rolled back.
        assertThat(accounts.findById(user).orElseThrow().getCashBalance()).isEqualByComparingTo("1000000.00");
        assertThat(ledger.findByUserIdOrderByOccurredAtAscIdAsc(user)).hasSize(1);
    }

    @Test
    void reconciliationDetectsTamperedBalances() {
        UUID orderId = placeAndReserve(OrderSide.BUY, "TCS", 1, "100.00");
        listener.onTradeExecuted(encode(Topics.TRADES_EXECUTED, trade(orderId, OrderSide.BUY, "TCS", 1, "100.00")));
        jdbc.update("UPDATE accounts SET cash_balance = cash_balance + 5 WHERE user_id = ?", user);
        jdbc.update("UPDATE holdings SET quantity = 2 WHERE user_id = ?", user);

        var report = reconciliation.check(user);
        assertThat(report.consistent()).isFalse();
        assertThat(report.issues()).extracting(i -> i.check())
                .contains("cash_balance_vs_ledger_sum", "cash_balance_vs_last_ledger_entry", "quantity_vs_ledger:TCS");
    }

    @Test
    void portfolioApiIsScopedToTheCaller() throws Exception {
        mvc.perform(get("/api/portfolio/summary").header("Authorization", TestJwts.bearer(TestJwts.user(user))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(user))
                .andExpect(jsonPath("$.cashBalance").value(1000000.00))
                .andExpect(jsonPath("$.priceSource").value("SIMULATED"));
        mvc.perform(get("/api/portfolio/summary").header("Authorization", TestJwts.bearer(TestJwts.user(999_999))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
        mvc.perform(get("/api/admin/reconciliation").header("Authorization", TestJwts.bearer(TestJwts.user(user))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/reconciliation").header("Authorization", TestJwts.bearer(TestJwts.admin(1))))
                .andExpect(status().isOk());
        mvc.perform(get("/api/portfolio/summary")).andExpect(status().isUnauthorized());
    }
}
