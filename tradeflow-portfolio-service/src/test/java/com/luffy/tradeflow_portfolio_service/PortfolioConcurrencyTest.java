package com.luffy.tradeflow_portfolio_service;

import com.luffy.tradeflow.common.events.EventCodec;
import com.luffy.tradeflow.common.events.Events;
import com.luffy.tradeflow.common.events.Topics;
import com.luffy.tradeflow.common.testsupport.Retry;
import com.luffy.tradeflow_portfolio_service.client.MarketDataClient;
import com.luffy.tradeflow_portfolio_service.domain.Reservation;
import com.luffy.tradeflow_portfolio_service.messaging.PortfolioEventListener;
import com.luffy.tradeflow_portfolio_service.repository.AccountRepository;
import com.luffy.tradeflow_portfolio_service.repository.HoldingRepository;
import com.luffy.tradeflow_portfolio_service.repository.ReservationRepository;
import com.luffy.tradeflow_portfolio_service.service.ReconciliationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrent event handling against one account. Conflicting transactions lose an
 * optimistic lock (or a unique-key race) and are retried, as the Kafka error handler
 * does in production. Functional race tests, not load tests.
 */
@SpringBootTest
@ActiveProfiles("test")
class PortfolioConcurrencyTest {

    private static final AtomicLong IDS = new AtomicLong(40_000);

    @Autowired PortfolioEventListener listener;
    @Autowired EventCodec codec;
    @Autowired AccountRepository accounts;
    @Autowired HoldingRepository holdings;
    @Autowired ReservationRepository reservations;
    @Autowired ReconciliationService reconciliation;
    @MockitoBean MarketDataClient marketData;

    private String enc(long user, String topic, Object payload) {
        return codec.encode(codec.wrap(topic, String.valueOf(user), payload));
    }

    private static void concurrently(List<Runnable> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (Runnable task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    Retry.onConflict(task);
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void competingBuyOrdersNeverReserveMoreCashThanAvailable() throws Exception {
        long user = IDS.incrementAndGet();
        listener.onUserRegistered(enc(user, Topics.USERS_REGISTERED, new Events.UserRegistered(user, "c@t.dev", "C")));
        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            UUID orderId = UUID.randomUUID();
            tasks.add(() -> listener.onOrderPlaced(enc(user, Topics.ORDERS_PLACED, new Events.OrderPlaced(orderId, user,
                    "TCS", Events.OrderSide.BUY, Events.OrderType.MARKET, 3000, new BigDecimal("100.00")))));
        }

        concurrently(tasks); // each needs 300,000 of 1,000,000

        var account = accounts.findById(user).orElseThrow();
        assertThat(reservations.findByUserIdAndStatus(user, Reservation.Status.ACTIVE)).hasSize(3);
        assertThat(account.getReservedCash()).isEqualByComparingTo("900000.00");
        assertThat(account.getReservedCash()).isLessThanOrEqualTo(account.getCashBalance());
        assertThat(reconciliation.check(user).consistent()).isTrue();
    }

    /**
     * orders.placed and orders.cancelled for the same order arrive on different topics and can be
     * handled at the same instant. Whatever the interleaving, the order must never end with an
     * ACTIVE reservation (funds locked for a cancelled order).
     */
    @org.junit.jupiter.api.RepeatedTest(20)
    void reservationRacingItsCancellationNeverLeaksFunds() throws Exception {
        long user = IDS.incrementAndGet();
        listener.onUserRegistered(enc(user, Topics.USERS_REGISTERED, new Events.UserRegistered(user, "x@t.dev", "X")));
        UUID orderId = UUID.randomUUID();
        String placed = enc(user, Topics.ORDERS_PLACED, new Events.OrderPlaced(orderId, user, "TCS",
                Events.OrderSide.BUY, Events.OrderType.MARKET, 1, new BigDecimal("100.00")));
        String cancelled = enc(user, Topics.ORDERS_CANCELLED, new Events.OrderCancelled(orderId, user, "timeout"));

        concurrently(List.of(() -> listener.onOrderPlaced(placed), () -> listener.onOrderCancelled(cancelled)));

        reservations.findById(orderId).ifPresent(r ->
                assertThat(r.getStatus()).isNotEqualTo(Reservation.Status.ACTIVE));
        assertThat(accounts.findById(user).orElseThrow().getReservedCash()).isEqualByComparingTo("0");
        assertThat(reconciliation.check(user).consistent()).isTrue();
    }

    @Test
    void simultaneousSettlementsIntoANewHoldingBookEveryTradeExactlyOnce() throws Exception {
        long user = IDS.incrementAndGet();
        listener.onUserRegistered(enc(user, Topics.USERS_REGISTERED, new Events.UserRegistered(user, "s@t.dev", "S")));
        List<Events.TradeExecuted> trades = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            UUID orderId = UUID.randomUUID();
            listener.onOrderPlaced(enc(user, Topics.ORDERS_PLACED, new Events.OrderPlaced(orderId, user, "INFY",
                    Events.OrderSide.BUY, Events.OrderType.MARKET, 10, new BigDecimal("100.00"))));
            trades.add(new Events.TradeExecuted(UUID.randomUUID(), orderId, user, "INFY", Events.OrderSide.BUY, 10,
                    new BigDecimal("100.00"), new BigDecimal("1000.00"), Instant.now()));
        }
        List<Runnable> tasks = new ArrayList<>();
        for (Events.TradeExecuted t : trades) {
            tasks.add(() -> listener.onTradeExecuted(enc(user, Topics.TRADES_EXECUTED, t)));
            tasks.add(() -> listener.onTradeExecuted(enc(user, Topics.TRADES_EXECUTED, t))); // concurrent duplicate
        }

        concurrently(tasks);

        var account = accounts.findById(user).orElseThrow();
        assertThat(account.getCashBalance()).isEqualByComparingTo("994000.00"); // 6 x 1,000 exactly once
        assertThat(account.getReservedCash()).isEqualByComparingTo("0");
        assertThat(holdings.findByUserIdAndSymbol(user, "INFY").orElseThrow().getQuantity()).isEqualTo(60);
        assertThat(reconciliation.check(user).issues()).isEmpty();
    }
}
