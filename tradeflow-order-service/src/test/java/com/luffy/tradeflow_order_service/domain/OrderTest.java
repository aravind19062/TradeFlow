package com.luffy.tradeflow_order_service.domain;

import com.luffy.tradeflow.common.events.Events.OrderSide;
import com.luffy.tradeflow.common.events.Events.OrderType;
import com.luffy.tradeflow.common.web.ApiException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderTest {

    private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");

    private static Order limit(OrderSide side, String limit) {
        return Order.place(1L, null, "TCS", side, OrderType.LIMIT, 10, new BigDecimal(limit), new BigDecimal(limit), NOW);
    }

    private static Order market(OrderSide side) {
        return Order.place(1L, null, "TCS", side, OrderType.MARKET, 10, null, new BigDecimal("100.00"), NOW);
    }

    @Test
    void newOrderIsPendingWithHistory() {
        Order order = market(OrderSide.BUY);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.getHistory()).extracting(OrderStatusChange::getToStatus).containsExactly(OrderStatus.PENDING);
    }

    @Test
    void transitionTableMatchesLifecycle() {
        assertThat(OrderStatus.PENDING.allowedNext()).containsExactlyInAnyOrder(
                OrderStatus.OPEN, OrderStatus.PARTIALLY_FILLED, OrderStatus.FILLED, OrderStatus.REJECTED);
        assertThat(OrderStatus.OPEN.allowedNext()).containsExactlyInAnyOrder(OrderStatus.PARTIALLY_FILLED,
                OrderStatus.FILLED, OrderStatus.CANCELLED, OrderStatus.EXPIRED);
        assertThat(OrderStatus.EXPIRED.isTerminal()).isTrue();
        assertThat(OrderStatus.FILLED.isTerminal()).isTrue();
        assertThat(OrderStatus.REJECTED.isTerminal()).isTrue();
        assertThat(OrderStatus.CANCELLED.isTerminal()).isTrue();
    }

    @Test
    void buyLimitIsMarketableAtOrBelowLimit() {
        Order order = limit(OrderSide.BUY, "100.00");
        assertThat(order.isMarketableAt(new BigDecimal("100.00"))).isTrue();
        assertThat(order.isMarketableAt(new BigDecimal("99.95"))).isTrue();
        assertThat(order.isMarketableAt(new BigDecimal("100.05"))).isFalse();
    }

    @Test
    void sellLimitIsMarketableAtOrAboveLimit() {
        Order order = limit(OrderSide.SELL, "100.00");
        assertThat(order.isMarketableAt(new BigDecimal("100.05"))).isTrue();
        assertThat(order.isMarketableAt(new BigDecimal("99.95"))).isFalse();
    }

    @Test
    void fillRecordsPriceAndQuantity() {
        Order order = limit(OrderSide.BUY, "100.00");
        order.open("reserved", NOW);
        order.fill(new BigDecimal("98.50"), NOW);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.FILLED);
        assertThat(order.getFilledQuantity()).isEqualTo(10);
        assertThat(order.getAverageFillPrice()).isEqualByComparingTo("98.50");
        assertThat(order.getHistory()).extracting(OrderStatusChange::getToStatus)
                .containsExactly(OrderStatus.PENDING, OrderStatus.OPEN, OrderStatus.FILLED);
    }

    @Test
    void fillAboveBuyLimitIsRefused() {
        Order order = limit(OrderSide.BUY, "100.00");
        assertThatThrownBy(() -> order.fill(new BigDecimal("101.00"), NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void pendingOrderCannotBeCancelledYet() {
        Order order = market(OrderSide.BUY);
        assertThatThrownBy(() -> order.cancel("user", NOW))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("ORDER_NOT_CANCELLABLE_YET"));
    }

    @Test
    void terminalOrdersRejectFurtherTransitions() {
        Order order = market(OrderSide.BUY);
        order.fill(new BigDecimal("100.00"), NOW);
        assertThatThrownBy(() -> order.cancel("user", NOW))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("INVALID_STATE_TRANSITION"));
        assertThatThrownBy(() -> order.reject("X", "late", NOW)).isInstanceOf(ApiException.class);
    }

    @Test
    void partialFillsTrackQuantityAndWeightedAverage() {
        Order order = Order.place(1, null, "TCS", OrderSide.BUY, OrderType.LIMIT, 100, new BigDecimal("100.00"),
                new BigDecimal("100.00"), NOW);
        order.open("reserved", NOW);
        order.fill(30, new BigDecimal("98.00"), NOW);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PARTIALLY_FILLED);
        assertThat(order.remainingQuantity()).isEqualTo(70);
        order.fill(20, new BigDecimal("100.00"), NOW);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PARTIALLY_FILLED);
        assertThat(order.getAverageFillPrice()).isEqualByComparingTo("98.80");
        order.fill(50, new BigDecimal("100.00"), NOW);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.FILLED);
        assertThat(order.getFilledQuantity()).isEqualTo(100);
    }

    @Test
    void aFillCannotExceedTheRemainderOrTheLimit() {
        Order order = Order.place(1, null, "TCS", OrderSide.BUY, OrderType.LIMIT, 10, new BigDecimal("100.00"),
                new BigDecimal("100.00"), NOW);
        order.open("reserved", NOW);
        order.fill(6, new BigDecimal("100.00"), NOW);
        assertThatThrownBy(() -> order.fill(5, new BigDecimal("100.00"), NOW)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> order.fill(1, new BigDecimal("100.05"), NOW)).isInstanceOf(IllegalStateException.class);
        assertThat(order.getFilledQuantity()).isEqualTo(6);
    }

    @Test
    void aMarketOrderCannotTradeBeyondItsProtection() {
        Order buy = Order.place(1, null, "TCS", OrderSide.BUY, OrderType.MARKET, 10, null, new BigDecimal("101.00"), NOW);
        assertThat(buy.isMarketableAt(new BigDecimal("101.00"))).isTrue();
        assertThat(buy.isMarketableAt(new BigDecimal("101.05"))).isFalse();
        Order sell = Order.place(1, null, "TCS", OrderSide.SELL, OrderType.MARKET, 10, null, new BigDecimal("99.00"), NOW);
        assertThat(sell.isMarketableAt(new BigDecimal("99.00"))).isTrue();
        assertThat(sell.isMarketableAt(new BigDecimal("98.95"))).isFalse();
    }

    @Test
    void aPartiallyFilledOrderCanBeCancelledOrExpiredButNotRejected() {
        assertThat(OrderStatus.PARTIALLY_FILLED.allowedNext()).containsExactlyInAnyOrder(OrderStatus.PARTIALLY_FILLED,
                OrderStatus.FILLED, OrderStatus.CANCELLED, OrderStatus.EXPIRED);
        assertThat(OrderStatus.PARTIALLY_FILLED.isTerminal()).isFalse();
    }
}
