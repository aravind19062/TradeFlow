package com.luffy.tradeflow_order_service.matching;

import com.luffy.tradeflow.common.events.EventCodec;
import com.luffy.tradeflow.common.events.Events;
import com.luffy.tradeflow.common.events.Events.OrderSide;
import com.luffy.tradeflow.common.events.Events.OrderType;
import com.luffy.tradeflow.common.events.Topics;
import com.luffy.tradeflow.common.money.Money;
import com.luffy.tradeflow.common.outbox.OutboxEventRepository;
import com.luffy.tradeflow.common.security.AuthenticatedUser;
import com.luffy.tradeflow.common.web.ApiException;
import com.luffy.tradeflow_order_service.client.MarketDataClient;
import com.luffy.tradeflow_order_service.domain.Order;
import com.luffy.tradeflow_order_service.domain.OrderStatus;
import com.luffy.tradeflow_order_service.domain.Trade;
import com.luffy.tradeflow_order_service.messaging.OrderEventListener;
import com.luffy.tradeflow_order_service.repository.OrderRepository;
import com.luffy.tradeflow_order_service.repository.TradeRepository;
import com.luffy.tradeflow_order_service.saga.OrderSagaRepository;
import com.luffy.tradeflow_order_service.saga.SagaRecoveryJob;
import com.luffy.tradeflow_order_service.saga.SagaState;
import com.luffy.tradeflow_order_service.service.OrderService;
import com.luffy.tradeflow_order_service.web.OrderDtos.PlaceOrderRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/** The DEMO matching engine through its real listeners, saga and database. Two levels keep the sums short. */
@SpringBootTest(properties = {"tradeflow.saga.scan-interval-ms=3600000", "tradeflow.orders.matching.levels=2"})
@ActiveProfiles("test")
class PartialFillIntegrationTest {

    private static final long USER = 7001;
    private static final BigDecimal PRICE = new BigDecimal("1000.00");
    private static final AtomicInteger SYMBOLS = new AtomicInteger();

    @Autowired OrderService orderService;
    @Autowired OrderEventListener listener;
    @Autowired SagaRecoveryJob recoveryJob;
    @Autowired OrderSagaRepository sagas;
    @Autowired OrderRepository orders;
    @Autowired TradeRepository trades;
    @Autowired OutboxEventRepository outbox;
    @Autowired EventCodec codec;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.transaction.support.TransactionTemplate tx;
    @Autowired MatchingProperties matching;
    @MockitoBean MarketDataClient marketData;

    private final AuthenticatedUser user = new AuthenticatedUser(USER, "m@test.dev", Set.of("USER"), "t");

    private String symbol() {
        String symbol = "PF" + SYMBOLS.incrementAndGet();
        when(marketData.quote(anyString())).thenReturn(new MarketDataClient.Quote(symbol, PRICE, 1, Instant.now(), "SIMULATED"));
        return symbol;
    }

    private String event(String topic, String key, Object payload) {
        return codec.encode(codec.wrap(topic, key, payload));
    }

    private void tick(String symbol, long tick, String price) {
        listener.onPriceUpdated(event(Topics.PRICES_UPDATED, symbol,
                new Events.PriceUpdated(symbol, new BigDecimal(price), tick, Instant.now(), "SIMULATED")));
    }

    private long depth(String symbol, long tick, OrderSide side) {
        var book = new SimulatedOrderBook(symbol, tick, side, PRICE, 0, matching);
        return book.depth(0) + book.depth(1);
    }

    private UUID place(String symbol, OrderSide side, OrderType type, long quantity, String limit) {
        return orderService.place(user, new PlaceOrderRequest(symbol, side, type, quantity,
                limit == null ? null : new BigDecimal(limit), null)).order().id();
    }

    private void reserve(UUID id) {
        listener.onReserved(event(Topics.ORDERS_RESERVED, id.toString(),
                new Events.OrderReserved(id, USER, BigDecimal.ZERO, 0)));
    }

    private UUID placed(String symbol, OrderSide side, OrderType type, long quantity, String limit) {
        UUID id = place(symbol, side, type, quantity, limit);
        reserve(id);
        return id;
    }

    private Order order(UUID id) {
        return orders.findById(id).orElseThrow();
    }

    private SagaState saga(UUID id) {
        return sagas.findById(id).orElseThrow().getState();
    }

    private List<Trade> tradesOf(UUID id) {
        return trades.findByOrderIdOrderByExecutedAtAsc(id);
    }

    private void confirm(UUID order, Trade trade) {
        listener.onTradeSettled(event(Topics.TRADES_SETTLED, order.toString(),
                new Events.TradeSettled(trade.getId(), order, USER)));
    }

    private void released(UUID id) {
        listener.onOrderReleased(event(Topics.ORDERS_RELEASED, id.toString(),
                new Events.OrderReleased(id, USER, Events.ReleaseOutcome.RELEASED)));
    }

    private void expireNow(UUID id) {
        jdbc.update("UPDATE order_sagas SET deadline_at = ? WHERE order_id = ?",
                Timestamp.from(Instant.now().minusSeconds(60)), id);
        recoveryJob.recoverOverdue();
    }

    private long filledSum(UUID id) {
        return tradesOf(id).stream().mapToLong(Trade::getQuantity).sum();
    }

    @Test
    void aLargeMarketBuyFillsWhatTheBookHoldsAndFinishesOnLaterTicks() {
        String symbol = symbol();
        tick(symbol, 1, "1000.00");
        long tickOne = depth(symbol, 1, OrderSide.BUY);
        long tickTwo = depth(symbol, 2, OrderSide.BUY);
        UUID id = placed(symbol, OrderSide.BUY, OrderType.MARKET, tickOne + 5, null);

        assertThat(order(id).getStatus()).isEqualTo(OrderStatus.PARTIALLY_FILLED);
        assertThat(order(id).getFilledQuantity()).isEqualTo(tickOne);
        assertThat(tradesOf(id)).extracting(t -> Money.price(t.getPrice()).toPlainString()).contains("1000.00", "1000.50");
        assertThat(saga(id)).isEqualTo(SagaState.RESTING);

        assertThat(tickTwo).isGreaterThan(5);
        tick(symbol, 2, "1000.00");
        Order done = order(id);
        assertThat(done.getStatus()).isEqualTo(OrderStatus.FILLED);
        assertThat(done.getFilledQuantity()).isEqualTo(tickOne + 5);
        assertThat(filledSum(id)).isEqualTo(tickOne + 5);
        BigDecimal value = tradesOf(id).stream().map(Trade::getGrossAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(done.getAverageFillPrice()).isEqualByComparingTo(
                value.divide(BigDecimal.valueOf(tickOne + 5), 4, java.math.RoundingMode.HALF_EVEN));

        // The saga only completes once every trade is confirmed, in any order.
        assertThat(saga(id)).isEqualTo(SagaState.AWAITING_SETTLEMENT);
        List<Trade> all = tradesOf(id);
        all.subList(1, all.size()).forEach(t -> confirm(id, t));
        assertThat(saga(id)).isEqualTo(SagaState.AWAITING_SETTLEMENT);
        confirm(id, all.get(0));
        assertThat(saga(id)).isEqualTo(SagaState.COMPLETED);
    }

    @Test
    void aMarketBuyNeverTradesAboveWhatWasReserved() {
        String symbol = symbol();
        tick(symbol, 1, "1000.00");
        UUID id = placed(symbol, OrderSide.BUY, OrderType.MARKET, 40_000, null);
        int afterFirstTick = tradesOf(id).size();
        tick(symbol, 2, "1009.90"); // the next level would be 1010.41, beyond the 1010.00 reserved
        Order o = order(id);
        assertThat(o.getReservationPrice()).isEqualByComparingTo("1010.00");
        assertThat(tradesOf(id)).allSatisfy(t -> assertThat(t.getPrice()).isLessThanOrEqualTo(o.getReservationPrice()));
        assertThat(tradesOf(id)).hasSize(afterFirstTick + 1);
    }

    @Test
    void ordersInOneTickShareItsLiquidity() {
        String symbol = symbol();
        tick(symbol, 1, "1000.00");
        long available = depth(symbol, 1, OrderSide.SELL);
        UUID first = placed(symbol, OrderSide.SELL, OrderType.MARKET, available - 3, null);
        UUID second = placed(symbol, OrderSide.SELL, OrderType.MARKET, 10, null);
        assertThat(order(first).getStatus()).isEqualTo(OrderStatus.FILLED);
        assertThat(order(second).getFilledQuantity()).isEqualTo(3);
        assertThat(order(second).getStatus()).isEqualTo(OrderStatus.PARTIALLY_FILLED);

        tick(symbol, 2, "1000.00");
        assertThat(order(second).getStatus()).isEqualTo(OrderStatus.FILLED);
    }

    @Test
    void theBestPriceGoesFirstThenTheOldest() {
        String symbol = symbol();
        tick(symbol, 1, "1100.00");
        long available = depth(symbol, 2, OrderSide.BUY);
        UUID older = placed(symbol, OrderSide.BUY, OrderType.LIMIT, available, "1001.00");
        UUID betterPrice = placed(symbol, OrderSide.BUY, OrderType.LIMIT, available, "1002.00");
        tick(symbol, 2, "1000.00");
        assertThat(order(betterPrice).getStatus()).isEqualTo(OrderStatus.FILLED);
        assertThat(order(older).getFilledQuantity()).isZero();
        assertThat(order(older).getStatus()).isEqualTo(OrderStatus.OPEN);
    }

    @Test
    void cancellingAPartialFillKeepsItsTradesAndReleasesTheRest() {
        String symbol = symbol();
        tick(symbol, 1, "1000.00");
        long tickOne = depth(symbol, 1, OrderSide.BUY);
        UUID id = placed(symbol, OrderSide.BUY, OrderType.LIMIT, tickOne + 50, "1000.50");

        var cancelled = orderService.cancel(user, id);
        assertThat(cancelled.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(cancelled.filledQuantity()).isEqualTo(tickOne);
        assertThat(cancelled.statusReason()).contains("of " + (tickOne + 50) + " filled");
        assertThat(outbox.findAll()).filteredOn(e -> e.getTopic().equals(Topics.ORDERS_CANCELLED)
                        && e.getPayload().contains(id.toString()))
                .singleElement().satisfies(e -> assertThat(e.getPayload()).contains("\"filledQuantity\":" + tickOne));
        assertThat(saga(id)).isEqualTo(SagaState.RELEASE_REQUESTED);

        // The release is confirmed before the trades: the saga stays open for them.
        released(id);
        assertThat(saga(id)).isEqualTo(SagaState.AWAITING_SETTLEMENT);
        tradesOf(id).forEach(t -> confirm(id, t));
        assertThat(saga(id)).isEqualTo(SagaState.RELEASED);

        // A cancelled remainder never trades again.
        int before = tradesOf(id).size();
        tick(symbol, 2, "1000.00");
        assertThat(tradesOf(id)).hasSize(before);
        assertThat(order(id).getFilledQuantity()).isEqualTo(tickOne);
        assertThatThrownBy(() -> orderService.cancel(user, id)).isInstanceOf(ApiException.class);
    }

    @Test
    void unconfirmedPartialTradesAreRedrivenAfterTheRelease() {
        String symbol = symbol();
        tick(symbol, 1, "1000.00");
        UUID id = placed(symbol, OrderSide.BUY, OrderType.LIMIT, depth(symbol, 1, OrderSide.BUY) + 50, "1000.50");
        orderService.cancel(user, id);
        released(id);
        long sentBefore = outbox.findAll().stream().filter(e -> e.getTopic().equals(Topics.TRADES_EXECUTED)
                && e.getPayload().contains(id.toString())).count();
        expireNow(id);
        assertThat(outbox.findAll()).filteredOn(e -> e.getTopic().equals(Topics.TRADES_EXECUTED)
                && e.getPayload().contains(id.toString())).hasSize((int) sentBefore + tradesOf(id).size());
        assertThat(outbox.findAll()).filteredOn(e -> e.getTopic().equals(Topics.ORDERS_CANCELLED)
                && e.getPayload().contains(id.toString())).hasSize(1);
    }

    @Test
    void aMarketOrderWithNoLiquidityInsideItsProtectionExpires() {
        String symbol = symbol();
        tick(symbol, 1, "1000.00");
        placed(symbol, OrderSide.BUY, OrderType.MARKET, depth(symbol, 1, OrderSide.BUY), null); // empties this tick
        UUID id = placed(symbol, OrderSide.BUY, OrderType.MARKET, 10, null);
        assertThat(order(id).getStatus()).isEqualTo(OrderStatus.OPEN);
        tick(symbol, 2, "1100.00"); // and then the price runs away from its protection
        assertThat(tradesOf(id)).isEmpty();
        assertThat(saga(id)).isEqualTo(SagaState.RESTING);

        expireNow(id);
        assertThat(order(id).getStatus()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(order(id).getRejectCode()).isEqualTo("INSUFFICIENT_LIQUIDITY");
        assertThat(saga(id)).isEqualTo(SagaState.RELEASE_REQUESTED);
        released(id);
        assertThat(saga(id)).isEqualTo(SagaState.RELEASED);
    }

    @Test
    void aMarketOrderCanExpireAfterAPartialFill() {
        String symbol = symbol();
        tick(symbol, 1, "1000.00");
        long tickOne = depth(symbol, 1, OrderSide.BUY);
        UUID id = placed(symbol, OrderSide.BUY, OrderType.MARKET, tickOne + 5, null);
        tick(symbol, 2, "1100.00"); // the price has run away from the protection
        assertThat(order(id).getFilledQuantity()).isEqualTo(tickOne);

        expireNow(id);
        Order expired = order(id);
        assertThat(expired.getStatus()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(expired.getRejectCode()).isEqualTo("INSUFFICIENT_LIQUIDITY");
        assertThat(expired.getFilledQuantity()).isEqualTo(tickOne);
        assertThat(expired.getStatusReason()).contains("with " + tickOne + " of " + (tickOne + 5) + " filled");
    }

    @Test
    void aPartiallyFilledLimitOrderKeepsItsPriceAndExpires() {
        String symbol = symbol();
        tick(symbol, 1, "1000.00");
        long tickOne = depth(symbol, 1, OrderSide.BUY);
        UUID id = placed(symbol, OrderSide.BUY, OrderType.LIMIT, tickOne + 5, "1000.50");
        tick(symbol, 2, "1001.00"); // above the limit: nothing more executes
        assertThat(order(id).getFilledQuantity()).isEqualTo(tickOne);
        assertThat(order(id).getStatus()).isEqualTo(OrderStatus.PARTIALLY_FILLED);

        expireNow(id);
        assertThat(order(id).getStatus()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(order(id).getRejectCode()).isNull();
        assertThat(order(id).getFilledQuantity()).isEqualTo(tickOne);
    }

    @Test
    void aRejectedOrderNeverReachesTheBook() {
        String symbol = symbol();
        tick(symbol, 1, "1000.00");
        UUID id = place(symbol, OrderSide.BUY, OrderType.MARKET, 10, null);
        listener.onRejected(event(Topics.ORDERS_REJECTED, id.toString(),
                new Events.OrderRejected(id, USER, "INSUFFICIENT_FUNDS", "needs more cash")));
        tick(symbol, 2, "1000.00");
        assertThat(order(id).getStatus()).isEqualTo(OrderStatus.REJECTED);
        assertThat(tradesOf(id)).isEmpty();
    }

    @Test
    void historyRecordsEveryExecution() {
        String symbol = symbol();
        tick(symbol, 1, "1000.00");
        long tickOne = depth(symbol, 1, OrderSide.BUY);
        UUID id = placed(symbol, OrderSide.BUY, OrderType.MARKET, tickOne + 5, null);
        tick(symbol, 2, "1000.00");
        var detail = orderService.get(user, id);
        assertThat(detail.history()).extracting(h -> h.to().name())
                .startsWith("PENDING", "PARTIALLY_FILLED").endsWith("FILLED");
        assertThat(detail.history().stream().filter(h -> h.reason().startsWith("Executed")).count())
                .isEqualTo(detail.trades().size());
    }
}
