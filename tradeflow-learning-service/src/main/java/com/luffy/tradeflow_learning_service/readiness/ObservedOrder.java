package com.luffy.tradeflow_learning_service.readiness;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * A DEMO order as seen from its events (evidence for exercises). Topics are not ordered relative
 * to each other, so fields fill in as the placed/reserved/cancelled/executed events arrive.
 */
@Entity
@Table(name = "observed_orders")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ObservedOrder {

    @Id
    @Column(name = "order_id")
    private UUID orderId;
    @Column(name = "user_id", nullable = false)
    private long userId;
    private String symbol;
    private String side;
    @Column(name = "order_type")
    private String orderType;
    private Long quantity;
    @Column(name = "observed_at", nullable = false)
    private Instant observedAt;
    @Column(name = "placed_at")
    private Instant placedAt;
    @Column(name = "reserved_at")
    private Instant reservedAt;
    @Column(name = "cancelled_at")
    private Instant cancelledAt;
    @Column(name = "cancel_reason")
    private String cancelReason;
    @Column(name = "filled_at")
    private Instant filledAt;

    ObservedOrder(UUID orderId, long userId, Instant now) {
        this.orderId = orderId;
        this.userId = userId;
        this.observedAt = now;
    }

    void placed(String symbol, String side, String type, long quantity, Instant at) {
        this.symbol = symbol;
        this.side = side;
        this.orderType = type;
        this.quantity = quantity;
        this.placedAt = at;
    }

    void reserved(Instant at) {
        this.reservedAt = at;
    }

    void cancelled(String reason, Instant at) {
        this.cancelReason = reason == null ? null : reason.substring(0, Math.min(reason.length(), 200));
        this.cancelledAt = at;
    }

    void filled(Instant at) {
        this.filledAt = at;
    }

    boolean isPlaced() {
        return placedAt != null;
    }
}
