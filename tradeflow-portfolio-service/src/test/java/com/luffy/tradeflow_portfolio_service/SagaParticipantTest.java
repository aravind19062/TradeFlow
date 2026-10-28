package com.luffy.tradeflow_portfolio_service;

import com.luffy.tradeflow.common.events.EventCodec;
import com.luffy.tradeflow.common.events.Events;
import com.luffy.tradeflow.common.events.Topics;
import com.luffy.tradeflow.common.outbox.OutboxEvent;
import com.luffy.tradeflow.common.outbox.OutboxEventRepository;
import com.luffy.tradeflow_portfolio_service.client.MarketDataClient;
import com.luffy.tradeflow_portfolio_service.client.OrderServiceClient;
import com.luffy.tradeflow_portfolio_service.client.OrderServiceClient.OrderStatusView;
import com.luffy.tradeflow_portfolio_service.domain.Reservation;
import com.luffy.tradeflow_portfolio_service.messaging.PortfolioEventListener;
import com.luffy.tradeflow_portfolio_service.repository.AccountRepository;
import com.luffy.tradeflow_portfolio_service.repository.ReservationRepository;
import com.luffy.tradeflow_portfolio_service.service.CrossServiceReconciliationService;
import com.luffy.tradeflow_portfolio_service.service.CrossServiceReconciliationService.CrossServiceIssue;
import com.luffy.tradeflow.common.web.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/** Portfolio's side of the saga: confirmations, safe compensation, tombstones, cross-service checks. */
@SpringBootTest(properties = {"tradeflow.portfolio.cross-check-stale-after=PT0S",
        "tradeflow.portfolio.cross-check-interval-ms=3600000"})
@ActiveProfiles("test")
class SagaParticipantTest {

    private static final AtomicLong IDS = new AtomicLong(20_000);

    @Autowired PortfolioEventListener listener;
    @Autowired EventCodec codec;
    @Autowired OutboxEventRepository outbox;
    @Autowired ReservationRepository reservations;
    @Autowired AccountRepository accounts;
    @Autowired CrossServiceReconciliationService crossService;
    @MockitoBean MarketDataClient marketData;
    @MockitoBean OrderServiceClient orderService;

    private long user;

    @BeforeEach
    void account() {
        user = IDS.incrementAndGet();
        listener.onUserRegistered(enc(Topics.USERS_REGISTERED, new Events.UserRegistered(user, "p@test.dev", "P")));
    }

    private String enc(String topic, Object payload) {
        return codec.encode(codec.wrap(topic, String.valueOf(user), payload));
    }

    private UUID reserveBuy(String price) {
        UUID orderId = UUID.randomUUID();
        listener.onOrderPlaced(enc(Topics.ORDERS_PLACED, new Events.OrderPlaced(orderId, user, "TCS",
                Events.OrderSide.BUY, Events.OrderType.MARKET, 1, new BigDecimal(price))));
        return orderId;
    }

    private List<String> payloadsOf(String topic, UUID orderId) {
        return outbox.findAll().stream().filter(e -> e.getTopic().equals(topic)
                && e.getPayload().contains(orderId.toString())).map(OutboxEvent::getPayload).toList();
    }

    private Events.TradeExecuted trade(UUID orderId, String price) {
        return new Events.TradeExecuted(UUID.randomUUID(), orderId, user, "TCS", Events.OrderSide.BUY, 1,
                new BigDecimal(price), new BigDecimal(price), Instant.now());
    }

    @Test
    void settlementIsConfirmedAndReconfirmedOnRedelivery() {
        UUID orderId = reserveBuy("100.00");
        Events.TradeExecuted t = trade(orderId, "100.00");
        listener.onTradeExecuted(enc(Topics.TRADES_EXECUTED, t));
        listener.onTradeExecuted(enc(Topics.TRADES_EXECUTED, t)); // re-drive with a new eventId
        assertThat(payloadsOf(Topics.TRADES_SETTLED, orderId)).hasSize(2);
        assertThat(accounts.findById(user).orElseThrow().getCashBalance()).isEqualByComparingTo("999900.00");
    }

    @Test
    void releaseOutcomesAndSettledReservationIsNeverReleased() {
        UUID open = reserveBuy("50.00");
        listener.onOrderCancelled(enc(Topics.ORDERS_CANCELLED, new Events.OrderCancelled(open, user, "user")));
        listener.onOrderCancelled(enc(Topics.ORDERS_CANCELLED, new Events.OrderCancelled(open, user, "again")));
        assertThat(payloadsOf(Topics.ORDERS_RELEASED, open)).hasSize(2)
                .anySatisfy(p -> assertThat(p).contains("\"RELEASED\""))
                .anySatisfy(p -> assertThat(p).contains("NOTHING_TO_RELEASE"));

        UUID filled = reserveBuy("100.00");
        listener.onTradeExecuted(enc(Topics.TRADES_EXECUTED, trade(filled, "100.00")));
        BigDecimal cashAfterTrade = accounts.findById(user).orElseThrow().getCashBalance();
        listener.onOrderCancelled(enc(Topics.ORDERS_CANCELLED, new Events.OrderCancelled(filled, user, "bogus")));
        assertThat(payloadsOf(Topics.ORDERS_RELEASED, filled)).singleElement()
                .satisfies(p -> assertThat(p).contains("ALREADY_SETTLED"));
        assertThat(accounts.findById(user).orElseThrow().getCashBalance()).isEqualByComparingTo(cashAfterTrade);
    }

    @Test
    void releaseBeforeReservationLeavesTombstoneThatRefusesLateReservation() {
        UUID orderId = UUID.randomUUID();
        listener.onOrderCancelled(enc(Topics.ORDERS_CANCELLED, new Events.OrderCancelled(orderId, user, "timeout")));
        listener.onOrderPlaced(enc(Topics.ORDERS_PLACED, new Events.OrderPlaced(orderId, user, "TCS",
                Events.OrderSide.BUY, Events.OrderType.MARKET, 1, new BigDecimal("10.00"))));
        assertThat(reservations.findById(orderId)).isEmpty();
        assertThat(payloadsOf(Topics.ORDERS_REJECTED, orderId)).singleElement()
                .satisfies(p -> assertThat(p).contains("ORDER_CANCELLED"));
        assertThat(accounts.findById(user).orElseThrow().getReservedCash()).isEqualByComparingTo("0");
    }

    @Test
    void crossServiceCheckReleasesLeakedReservationsAndReportsTheRest() {
        UUID leaked = reserveBuy("10.00");
        UUID unsettled = reserveBuy("20.00");
        UUID orphan = reserveBuy("30.00");
        UUID inProgress = reserveBuy("40.00");
        when(orderService.statuses(anyCollection(), anyString())).thenReturn(List.of(
                new OrderStatusView(leaked, "REJECTED", "RELEASE_REQUESTED"),
                new OrderStatusView(unsettled, "FILLED", "AWAITING_SETTLEMENT"),
                new OrderStatusView(inProgress, "OPEN", "RESTING")));

        var report = crossService.run();

        assertThat(report.orderServiceReachable()).isTrue();
        assertThat(report.issues()).extracting(CrossServiceIssue::orderId, CrossServiceIssue::type, CrossServiceIssue::action)
                .contains(org.assertj.core.groups.Tuple.tuple(leaked, "LEAKED_RESERVATION", "RELEASED_AUTOMATICALLY"),
                        org.assertj.core.groups.Tuple.tuple(unsettled, "UNSETTLED_FILL", "AWAITING_SETTLEMENT_REDRIVE"),
                        org.assertj.core.groups.Tuple.tuple(orphan, "ORPHAN_RESERVATION", "NEEDS_OPERATOR"));
        assertThat(report.issues()).extracting(CrossServiceIssue::orderId).doesNotContain(inProgress);
        assertThat(reservations.findById(leaked).orElseThrow().getStatus()).isEqualTo(Reservation.Status.RELEASED);
        assertThat(reservations.findById(unsettled).orElseThrow().getStatus()).isEqualTo(Reservation.Status.ACTIVE);
        assertThat(report.needsAttention()).isTrue();
    }

    @Test
    void crossServiceCheckReportsUnreachableOrderServiceWithoutGuessing() {
        reserveBuy("10.00");
        when(orderService.statuses(anyCollection(), anyString()))
                .thenThrow(ApiException.dependencyUnavailable("order-service", "down"));
        var report = crossService.run();
        assertThat(report.orderServiceReachable()).isFalse();
        assertThat(report.autoReleased()).isZero();
        assertThat(report.needsAttention()).isTrue();
    }
}
