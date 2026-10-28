package com.luffy.tradeflow_order_service.saga;

import com.luffy.tradeflow.common.events.EventCodec;
import com.luffy.tradeflow.common.events.Events;
import com.luffy.tradeflow.common.events.Topics;
import com.luffy.tradeflow.common.outbox.OutboxEvent;
import com.luffy.tradeflow.common.outbox.OutboxEventRepository;
import com.luffy.tradeflow.common.security.AuthenticatedUser;
import com.luffy.tradeflow.common.testsupport.TestJwts;
import com.luffy.tradeflow_order_service.client.MarketDataClient;
import com.luffy.tradeflow_order_service.domain.Order;
import com.luffy.tradeflow_order_service.domain.OrderStatus;
import com.luffy.tradeflow_order_service.messaging.OrderEventListener;
import com.luffy.tradeflow_order_service.repository.OrderRepository;
import com.luffy.tradeflow_order_service.repository.TradeRepository;
import com.luffy.tradeflow_order_service.service.OrderService;
import com.luffy.tradeflow_order_service.web.OrderDtos.PlaceOrderRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Every saga step, timeout and compensation, driven deterministically. */
@SpringBootTest(properties = "tradeflow.saga.scan-interval-ms=3600000")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrderSagaRecoveryTest {

    private static final long USER = 901;

    @Autowired OrderService orderService;
    @Autowired OrderEventListener listener;
    @Autowired SagaRecoveryJob recoveryJob;
    @Autowired OrderSagaRepository sagas;
    @Autowired OrderRepository orders;
    @Autowired TradeRepository trades;
    @Autowired OutboxEventRepository outbox;
    @Autowired EventCodec codec;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired MockMvc mvc;
    @MockitoBean MarketDataClient marketData;

    private final AuthenticatedUser user = new AuthenticatedUser(USER, "s@test.dev", Set.of("USER"), "t");

    @BeforeEach
    void quotes() {
        when(marketData.quote(anyString())).thenReturn(
                new MarketDataClient.Quote("TCS", new BigDecimal("100.00"), 1, Instant.now(), "SIMULATED"));
    }

    private UUID place(String type, String limit) {
        return orderService.place(user, new PlaceOrderRequest("TCS", Events.OrderSide.BUY,
                Events.OrderType.valueOf(type), 2L, limit == null ? null : new BigDecimal(limit), null)).order().id();
    }

    private String event(String topic, String key, Object payload) {
        return codec.encode(codec.wrap(topic, key, payload));
    }

    private void overdue(UUID orderId) {
        jdbc.update("UPDATE order_sagas SET deadline_at = ? WHERE order_id = ?",
                Timestamp.from(Instant.now().minusSeconds(60)), orderId);
    }

    private void recover(UUID orderId) {
        tx.executeWithoutResult(s -> recoveryJob.recover(orderId));
    }

    private SagaState state(UUID orderId) {
        return sagas.findById(orderId).orElseThrow().getState();
    }

    private List<String> outboxTopics(UUID orderId, Long userKey) {
        return outbox.findAll().stream()
                .filter(e -> e.getPayload().contains(orderId.toString()))
                .map(OutboxEvent::getTopic).toList();
    }

    @Test
    void happyPathCompletesOnlyAfterSettlementConfirmation() {
        UUID id = place("MARKET", null);
        assertThat(state(id)).isEqualTo(SagaState.AWAITING_RESERVATION);
        listener.onReserved(event(Topics.ORDERS_RESERVED, id.toString(),
                new Events.OrderReserved(id, USER, new BigDecimal("200.00"), 0)));
        assertThat(state(id)).isEqualTo(SagaState.AWAITING_SETTLEMENT);
        UUID tradeId = trades.findByOrderIdOrderByExecutedAtAsc(id).get(0).getId();
        listener.onTradeSettled(event(Topics.TRADES_SETTLED, id.toString(), new Events.TradeSettled(tradeId, id, USER)));
        assertThat(state(id)).isEqualTo(SagaState.COMPLETED);
    }

    @Test
    void reservationTimeoutRejectsAndCompensatesThenLateReservationIsReleasedToo() {
        UUID id = place("MARKET", null);
        overdue(id);
        recover(id);
        Order order = orders.findById(id).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.REJECTED);
        assertThat(order.getRejectCode()).isEqualTo("RESERVATION_TIMEOUT");
        assertThat(state(id)).isEqualTo(SagaState.RELEASE_REQUESTED);
        assertThat(outboxTopics(id, USER)).containsExactly(Topics.ORDERS_PLACED, Topics.ORDERS_CANCELLED);

        // portfolio's reservation reply arrives late: compensate again, never execute
        listener.onReserved(event(Topics.ORDERS_RESERVED, id.toString(),
                new Events.OrderReserved(id, USER, new BigDecimal("200.00"), 0)));
        assertThat(trades.findByOrderIdOrderByExecutedAtAsc(id)).isEmpty();
        assertThat(outboxTopics(id, USER)).containsExactly(Topics.ORDERS_PLACED, Topics.ORDERS_CANCELLED,
                Topics.ORDERS_CANCELLED);

        listener.onOrderReleased(event(Topics.ORDERS_RELEASED, id.toString(),
                new Events.OrderReleased(id, USER, Events.ReleaseOutcome.RELEASED)));
        assertThat(state(id)).isEqualTo(SagaState.RELEASED);
    }

    @Test
    void unconfirmedSettlementIsRedrivenNeverReleasedAndFailsAfterBudget() throws Exception {
        UUID id = place("MARKET", null);
        listener.onReserved(event(Topics.ORDERS_RESERVED, id.toString(),
                new Events.OrderReserved(id, USER, new BigDecimal("200.00"), 0)));
        for (int attempt = 1; attempt <= 3; attempt++) {
            overdue(id);
            recover(id);
            assertThat(sagas.findById(id).orElseThrow().getRedriveCount()).isEqualTo(attempt);
        }
        overdue(id);
        recover(id);
        assertThat(state(id)).isEqualTo(SagaState.SETTLEMENT_FAILED);
        List<String> topics = outboxTopics(id, USER);
        assertThat(topics).filteredOn(Topics.TRADES_EXECUTED::equals).hasSize(4); // original + 3 re-drives
        assertThat(topics).doesNotContain(Topics.ORDERS_CANCELLED);            // the trade is real: no release
        UUID tradeId = trades.findByOrderIdOrderByExecutedAtAsc(id).get(0).getId();
        assertThat(outbox.findAll()).filteredOn(e -> e.getTopic().equals(Topics.TRADES_EXECUTED)
                        && e.getPayload().contains(id.toString()))
                .allSatisfy(e -> assertThat(e.getPayload()).contains(tradeId.toString())); // same trade id every time

        mvc.perform(get("/actuator/health").header("Authorization", TestJwts.bearer(TestJwts.admin(1))))
                .andExpect(jsonPath("$.components.orderPipeline.status").value("DOWN"));
        mvc.perform(post("/api/admin/orders/sagas/" + id + "/retry")
                        .header("Authorization", TestJwts.bearer(TestJwts.user(USER))))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/orders/sagas/" + id + "/retry")
                        .header("Authorization", TestJwts.bearer(TestJwts.admin(1))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("AWAITING_SETTLEMENT"));
        listener.onTradeSettled(event(Topics.TRADES_SETTLED, id.toString(), new Events.TradeSettled(tradeId, id, USER)));
        assertThat(state(id)).isEqualTo(SagaState.COMPLETED);
    }

    @Test
    void restingLimitOrderExpiresAndReleases() {
        UUID id = place("LIMIT", "1.00");
        listener.onReserved(event(Topics.ORDERS_RESERVED, id.toString(),
                new Events.OrderReserved(id, USER, new BigDecimal("2.00"), 0)));
        assertThat(state(id)).isEqualTo(SagaState.RESTING);
        overdue(id);
        recover(id);
        assertThat(orders.findById(id).orElseThrow().getStatus()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(state(id)).isEqualTo(SagaState.RELEASE_REQUESTED);
        assertThat(outboxTopics(id, USER)).endsWith(Topics.ORDERS_CANCELLED);
    }

    @Test
    void releaseOfAnAlreadySettledReservationIsFlaggedNotIgnored() {
        UUID id = place("LIMIT", "1.00");
        listener.onReserved(event(Topics.ORDERS_RESERVED, id.toString(),
                new Events.OrderReserved(id, USER, new BigDecimal("2.00"), 0)));
        orderService.cancel(user, id);
        listener.onOrderReleased(event(Topics.ORDERS_RELEASED, id.toString(),
                new Events.OrderReleased(id, USER, Events.ReleaseOutcome.ALREADY_SETTLED)));
        assertThat(state(id)).isEqualTo(SagaState.RELEASE_FAILED);
    }

    @Test
    void internalStatusEndpointRequiresServiceRole() throws Exception {
        UUID id = place("MARKET", null);
        mvc.perform(get("/internal/orders/status?ids=" + id)
                        .header("Authorization", TestJwts.bearer(TestJwts.user(USER))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/internal/orders/status?ids=" + id)
                        .header("Authorization", TestJwts.bearer(TestJwts.service())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("PENDING"))
                .andExpect(jsonPath("$[0].sagaState").value("AWAITING_RESERVATION"));
    }
}
