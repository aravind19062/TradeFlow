package com.luffy.tradeflow_order_service.domain;

import com.luffy.tradeflow.common.events.Events.OrderSide;
import com.luffy.tradeflow.common.events.Events.OrderType;
import com.luffy.tradeflow.common.money.Money;
import com.luffy.tradeflow.common.web.ApiException;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Aggregate root for an order. All status changes go through {@link #transitionTo}. */
@Entity
@Table(name = "orders")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private long userId;

    @Column(name = "client_order_id")
    private String clientOrderId;

    @Column(nullable = false)
    private String symbol;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderSide side;

    @Enumerated(EnumType.STRING)
    @Column(name = "order_type", nullable = false)
    private OrderType type;

    @Column(nullable = false)
    private long quantity;

    @Column(name = "limit_price")
    private BigDecimal limitPrice;

    /** Sell stop / stop-limit: the bid at or below which the order triggers. */
    @Column(name = "trigger_price")
    private BigDecimal triggerPrice;

    /** When a stop order triggered (a triggered STOP_LIMIT then rests as a limit order). */
    @Column(name = "triggered_at")
    private Instant triggeredAt;

    /**
     * Per-share price used to size the reservation and, for MARKET orders, the worst price they may
     * trade at: the accepted ask/bid plus price protection (MARKET), the limit (LIMIT, STOP_LIMIT) or
     * the trigger (STOP; a SELL reserves shares, not cash).
     */
    @Column(name = "reservation_price", nullable = false)
    private BigDecimal reservationPrice;

    /** MARKET only: the ask/bid when accepted. Anchors the book if no tick has been seen yet. */
    @Column(name = "quoted_price")
    private BigDecimal quotedPrice;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    @Column(name = "reject_code")
    private String rejectCode;

    @Column(name = "status_reason")
    private String statusReason;

    @Column(name = "filled_quantity", nullable = false)
    private long filledQuantity;

    @Column(name = "average_fill_price")
    private BigDecimal averageFillPrice;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Optimistic lock: a concurrent fill and cancel cannot both succeed. */
    @Version
    private long version;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    private List<OrderStatusChange> history = new ArrayList<>();

    public static Order place(long userId, String clientOrderId, String symbol, OrderSide side, OrderType type,
                              long quantity, BigDecimal limitPrice, BigDecimal reservationPrice, Instant now) {
        return place(userId, clientOrderId, symbol, side, type, quantity, limitPrice, null, reservationPrice, now);
    }

    public static Order place(long userId, String clientOrderId, String symbol, OrderSide side, OrderType type,
                              long quantity, BigDecimal limitPrice, BigDecimal triggerPrice,
                              BigDecimal reservationPrice, Instant now) {
        return place(userId, clientOrderId, symbol, side, type, quantity, limitPrice, triggerPrice, reservationPrice,
                null, now);
    }

    public static Order place(long userId, String clientOrderId, String symbol, OrderSide side, OrderType type,
                              long quantity, BigDecimal limitPrice, BigDecimal triggerPrice,
                              BigDecimal reservationPrice, BigDecimal quotedPrice, Instant now) {
        Order order = new Order();
        order.id = UUID.randomUUID();
        order.userId = userId;
        order.clientOrderId = clientOrderId;
        order.symbol = symbol;
        order.side = side;
        order.type = type;
        order.quantity = quantity;
        order.limitPrice = limitPrice;
        order.triggerPrice = triggerPrice;
        order.reservationPrice = reservationPrice;
        order.quotedPrice = quotedPrice;
        order.status = OrderStatus.PENDING;
        order.createdAt = now;
        order.updatedAt = now;
        order.history.add(new OrderStatusChange(order, null, OrderStatus.PENDING,
                "Accepted; awaiting funds/position reservation", now));
        return order;
    }

    /**
     * Would a fill at {@code price} respect the order's price limit? A (triggered) STOP has none.
     * LIMIT and STOP_LIMIT orders must not buy above / sell below the limit, and a MARKET order must
     * stay inside its price protection, which is also what keeps a buy within its reserved funds.
     */
    public boolean isMarketableAt(BigDecimal price) {
        if (type == OrderType.STOP) {
            return true;
        }
        BigDecimal worst = type == OrderType.MARKET ? reservationPrice : limitPrice;
        return side == OrderSide.BUY ? price.compareTo(worst) <= 0 : price.compareTo(worst) >= 0;
    }

    public long remainingQuantity() {
        return quantity - filledQuantity;
    }

    public boolean isStop() {
        return type == OrderType.STOP || type == OrderType.STOP_LIMIT;
    }

    /**
     * Applies a simulated quote to a working (OPEN, or just-reserved) order and returns the fill price
     * if it should execute now. Buys trade at the ask, sells at the bid. A sell stop triggers when the
     * bid falls to its trigger: a STOP then sells at the bid; a STOP_LIMIT becomes a resting limit sell.
     */
    public java.util.Optional<BigDecimal> evaluate(Touch touch, Instant now) {
        if (isStop() && triggeredAt == null) {
            if (touch.bid().compareTo(triggerPrice) > 0) {
                return java.util.Optional.empty();
            }
            triggeredAt = now;
            history.add(new OrderStatusChange(this, status, status, "Triggered: bid " + touch.bid()
                    + " reached the stop " + triggerPrice + (type == OrderType.STOP ? "; selling at market"
                    : "; now a limit sell at " + limitPrice), now));
        }
        BigDecimal executable = side == OrderSide.BUY ? touch.ask() : touch.bid();
        return isMarketableAt(executable) ? java.util.Optional.of(executable) : java.util.Optional.empty();
    }

    public void open(String reason, Instant now) {
        transitionTo(OrderStatus.OPEN, reason, now);
    }

    /** Fills everything still unfilled at one price. */
    public void fill(BigDecimal price, Instant now) {
        fill(remainingQuantity(), price, now);
    }

    /** Records one execution; the average fill price is weighted by quantity across executions. */
    public void fill(long executed, BigDecimal price, Instant now) {
        if (executed <= 0 || executed > remainingQuantity()) {
            throw new IllegalStateException("Cannot fill " + executed + " of an order with "
                    + remainingQuantity() + " remaining");
        }
        if (!isMarketableAt(price)) {
            throw new IllegalStateException("Fill price " + price + " violates the limit of order " + id);
        }
        long filledAfter = filledQuantity + executed;
        boolean complete = filledAfter == quantity;
        transitionTo(complete ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED,
                filledQuantity == 0 && complete ? "Filled " + quantity + " @ " + price
                        : "Executed " + executed + " @ " + price + " (" + filledAfter + " of " + quantity + " filled)",
                now);
        BigDecimal value = price.multiply(BigDecimal.valueOf(executed));
        if (filledQuantity > 0) {
            value = value.add(averageFillPrice.multiply(BigDecimal.valueOf(filledQuantity)));
        }
        this.averageFillPrice = value.divide(BigDecimal.valueOf(filledAfter), Money.COST_SCALE, Money.ROUNDING);
        this.filledQuantity = filledAfter;
    }

    public void reject(String code, String reason, Instant now) {
        transitionTo(OrderStatus.REJECTED, reason, now);
        this.rejectCode = code;
    }

    public void cancel(String reason, Instant now) {
        if (status == OrderStatus.PENDING) {
            throw ApiException.conflict("ORDER_NOT_CANCELLABLE_YET",
                    "The order is still being checked for funds; retry in a moment");
        }
        transitionTo(OrderStatus.CANCELLED, reason, now);
    }

    /** A working order outlived its time-to-live (its reservation is then released). */
    public void expire(String reason, Instant now) {
        transitionTo(OrderStatus.EXPIRED, reason, now);
    }

    /** A MARKET order couldn't complete inside its price protection before its time ran out. */
    public void expireForLiquidity(String reason, Instant now) {
        transitionTo(OrderStatus.EXPIRED, reason, now);
        this.rejectCode = "INSUFFICIENT_LIQUIDITY";
    }

    private void transitionTo(OrderStatus next, String reason, Instant now) {
        if (!status.canTransitionTo(next)) {
            throw ApiException.conflict("INVALID_STATE_TRANSITION",
                    "Order " + id + " cannot move from " + status + " to " + next);
        }
        history.add(new OrderStatusChange(this, status, next, reason, now));
        this.status = next;
        this.statusReason = reason;
        this.updatedAt = now;
    }
}
