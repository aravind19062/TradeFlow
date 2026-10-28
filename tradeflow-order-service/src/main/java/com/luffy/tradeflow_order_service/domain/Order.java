package com.luffy.tradeflow_order_service.domain;

import com.luffy.tradeflow.common.events.Events.OrderSide;
import com.luffy.tradeflow.common.events.Events.OrderType;
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

    /** Per-share price used to size the reservation: accepted quote (MARKET) or limit (LIMIT). */
    @Column(name = "reservation_price", nullable = false)
    private BigDecimal reservationPrice;

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
        Order order = new Order();
        order.id = UUID.randomUUID();
        order.userId = userId;
        order.clientOrderId = clientOrderId;
        order.symbol = symbol;
        order.side = side;
        order.type = type;
        order.quantity = quantity;
        order.limitPrice = limitPrice;
        order.reservationPrice = reservationPrice;
        order.status = OrderStatus.PENDING;
        order.createdAt = now;
        order.updatedAt = now;
        order.history.add(new OrderStatusChange(order, null, OrderStatus.PENDING,
                "Accepted; awaiting funds/position reservation", now));
        return order;
    }

    /** Would a fill at {@code price} respect the limit? MARKET orders are always marketable. */
    public boolean isMarketableAt(BigDecimal price) {
        if (type == OrderType.MARKET) {
            return true;
        }
        return side == OrderSide.BUY ? price.compareTo(limitPrice) <= 0 : price.compareTo(limitPrice) >= 0;
    }

    public void open(String reason, Instant now) {
        transitionTo(OrderStatus.OPEN, reason, now);
    }

    public void fill(BigDecimal price, Instant now) {
        if (!isMarketableAt(price)) {
            throw new IllegalStateException("Fill price " + price + " violates limit " + limitPrice);
        }
        transitionTo(OrderStatus.FILLED, "Filled " + quantity + " @ " + price, now);
        this.filledQuantity = quantity;
        this.averageFillPrice = price;
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

    /** A resting LIMIT order outlived its time-to-live (its reservation is then released). */
    public void expire(String reason, Instant now) {
        transitionTo(OrderStatus.EXPIRED, reason, now);
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
