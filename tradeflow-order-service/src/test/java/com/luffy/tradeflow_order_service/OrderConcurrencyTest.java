package com.luffy.tradeflow_order_service;

import com.luffy.tradeflow.common.events.EventCodec;
import com.luffy.tradeflow.common.events.Events;
import com.luffy.tradeflow.common.events.Topics;
import com.luffy.tradeflow.common.outbox.OutboxEventRepository;
import com.luffy.tradeflow.common.security.AuthenticatedUser;
import com.luffy.tradeflow.common.testsupport.Retry;
import com.luffy.tradeflow.common.web.ApiException;
import com.luffy.tradeflow_order_service.client.MarketDataClient;
import com.luffy.tradeflow_order_service.domain.OrderStatus;
import com.luffy.tradeflow_order_service.messaging.OrderEventListener;
import com.luffy.tradeflow_order_service.repository.OrderRepository;
import com.luffy.tradeflow_order_service.service.OrderService;
import com.luffy.tradeflow_order_service.web.OrderDtos.PlaceOrderRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Real threads against the real service and database (H2). Functional race tests,
 * not load tests: they prove invariants under contention, not throughput.
 */
@SpringBootTest(properties = "tradeflow.saga.scan-interval-ms=3600000")
@ActiveProfiles("test")
class OrderConcurrencyTest {

    private static final AtomicLong USERS = new AtomicLong(5000);

    @Autowired OrderService orderService;
    @Autowired OrderEventListener listener;
    @Autowired OrderRepository orders;
    @Autowired OutboxEventRepository outbox;
    @Autowired EventCodec codec;
    @MockitoBean MarketDataClient marketData;

    @BeforeEach
    void quotes() {
        when(marketData.quote(anyString())).thenReturn(
                new MarketDataClient.Quote("TCS", new BigDecimal("100.00"), 1, Instant.now(), "SIMULATED"));
    }

    private static <T> List<T> runConcurrently(int threads, Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void duplicateConcurrentSubmissionsCreateExactlyOneOrder() throws Exception {
        long userId = USERS.incrementAndGet();
        AuthenticatedUser user = new AuthenticatedUser(userId, "c@test.dev", Set.of("USER"), "t");
        PlaceOrderRequest request = new PlaceOrderRequest("TCS", Events.OrderSide.BUY, Events.OrderType.MARKET, 3L,
                null, "same-key");

        List<OrderService.PlacementResult> results = runConcurrently(10, () -> orderService.place(user, request));

        assertThat(results).extracting(r -> r.order().id()).containsOnly(results.get(0).order().id());
        assertThat(results).filteredOn(OrderService.PlacementResult::created).hasSize(1);
        assertThat(outbox.findAll()).filteredOn(e -> e.getTopic().equals(Topics.ORDERS_PLACED)
                && e.getPayload().contains(results.get(0).order().id().toString())).hasSize(1);
    }

    /** A cancel and a marketable price tick race for the same OPEN order: exactly one may win. */
    @RepeatedTest(15)
    void cancelRacingExecutionHasExactlyOneOutcome() throws Exception {
        long userId = USERS.incrementAndGet();
        AuthenticatedUser user = new AuthenticatedUser(userId, "r@test.dev", Set.of("USER"), "t");
        String symbol = "SYM" + userId;
        when(marketData.quote(symbol)).thenReturn(
                new MarketDataClient.Quote(symbol, new BigDecimal("100.00"), 1, Instant.now(), "SIMULATED"));
        UUID id = orderService.place(user, new PlaceOrderRequest(symbol, Events.OrderSide.BUY,
                Events.OrderType.LIMIT, 1L, new BigDecimal("90.00"), null)).order().id();
        listener.onReserved(codec.encode(codec.wrap(Topics.ORDERS_RESERVED, id.toString(),
                new Events.OrderReserved(id, userId, new BigDecimal("90.00"), 0))));
        assertThat(orders.findById(id).orElseThrow().getStatus()).isEqualTo(OrderStatus.OPEN);
        String tick = codec.encode(codec.wrap(Topics.PRICES_UPDATED, symbol,
                new Events.PriceUpdated(symbol, new BigDecimal("85.00"), 1_000 + userId, Instant.now(), "SIMULATED")));

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<String> cancel = pool.submit(() -> {
            start.await();
            try {
                orderService.cancel(user, id);
                return "cancelled";
            } catch (ObjectOptimisticLockingFailureException e) {
                return "conflict"; // HTTP 409 CONCURRENT_MODIFICATION to the user
            } catch (ApiException e) {
                return e.getCode();   // INVALID_STATE_TRANSITION: the fill won
            }
        });
        Future<?> fill = pool.submit(() -> {
            start.await();
            Retry.onConflict(() -> listener.onPriceUpdated(tick)); // Kafka would redeliver after a conflict
            return null;
        });
        start.countDown();
        String cancelOutcome = cancel.get();
        fill.get();
        pool.shutdownNow();

        OrderStatus finalStatus = orders.findById(id).orElseThrow().getStatus();
        long executed = outbox.findAll().stream().filter(e -> e.getTopic().equals(Topics.TRADES_EXECUTED)
                && e.getPayload().contains(id.toString())).count();
        long releases = outbox.findAll().stream().filter(e -> e.getTopic().equals(Topics.ORDERS_CANCELLED)
                && e.getPayload().contains(id.toString())).count();
        if (finalStatus == OrderStatus.CANCELLED) {
            assertThat(cancelOutcome).isEqualTo("cancelled");
            assertThat(executed).isZero();
            assertThat(releases).isEqualTo(1);
        } else {
            assertThat(finalStatus).isEqualTo(OrderStatus.FILLED);
            assertThat(cancelOutcome).isIn("conflict", "INVALID_STATE_TRANSITION");
            assertThat(executed).isEqualTo(1);
            assertThat(releases).isZero();
        }
    }
}
