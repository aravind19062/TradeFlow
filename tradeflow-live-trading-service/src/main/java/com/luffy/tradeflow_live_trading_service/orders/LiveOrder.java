package com.luffy.tradeflow_live_trading_service.orders;

import com.luffy.tradeflow_live_trading_service.broker.BrokerAdapter;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** A real-money order sent to the broker. Ids start with "L-" so a demo order id can never match one. */
@Entity
@Table(name = "live_orders")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LiveOrder {

    @Id
    private String id;
    @Column(name = "user_id", nullable = false)
    private long userId;
    @Column(name = "connection_id", nullable = false)
    private UUID connectionId;
    @Column(name = "client_order_id", nullable = false)
    private String clientOrderId;
    @Column(name = "broker_order_id")
    private String brokerOrderId;
    @Column(nullable = false)
    private String exchange;
    @Column(nullable = false)
    private String symbol;
    @Column(nullable = false)
    private String side;
    @Column(name = "order_type", nullable = false)
    private String orderType;
    @Column(nullable = false)
    private long quantity;
    @Column(name = "limit_price")
    private BigDecimal limitPrice;
    @Column(name = "trigger_price")
    private BigDecimal triggerPrice;
    @Column(name = "order_value", nullable = false)
    private BigDecimal orderValue;
    @Column(nullable = false)
    private String status;
    @Column(name = "status_message")
    private String statusMessage;
    @Column(name = "filled_quantity", nullable = false)
    private long filledQuantity;
    @Column(name = "average_price")
    private BigDecimal averagePrice;
    @Column(name = "confirmation_id", nullable = false)
    private UUID confirmationId;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Column(name = "broker_updated_at")
    private Instant brokerUpdatedAt;
    @Column(name = "review_reason")
    private String reviewReason;
    @Column(name = "reconcile_checks", nullable = false)
    private int reconcileChecks;
    @Column(name = "last_reconciled_at")
    private Instant lastReconciledAt;
    @Version
    private long version;

    LiveOrder(long userId, UUID connectionId, String clientOrderId, OrderParams p, BigDecimal orderValue,
              UUID confirmationId, Instant now) {
        this.id = "L-" + UUID.randomUUID();
        this.userId = userId;
        this.connectionId = connectionId;
        this.clientOrderId = clientOrderId;
        this.exchange = p.exchange();
        this.symbol = p.symbol();
        this.side = p.side();
        this.orderType = p.type();
        this.quantity = p.quantity();
        this.limitPrice = p.limitPrice();
        this.triggerPrice = p.triggerPrice();
        this.orderValue = orderValue;
        this.status = LiveOrderStatus.SUBMITTING;
        this.confirmationId = confirmationId;
        this.createdAt = now;
        this.updatedAt = now;
    }

    void acknowledged(String brokerOrderId, String status, String message, Instant now) {
        this.brokerOrderId = brokerOrderId;
        this.status = status;
        this.statusMessage = message;
        this.updatedAt = now;
    }

    /** {@code status} is REJECTED (the broker definitely refused) or UNKNOWN (outcome not known). */
    void failed(String status, String message, Instant now) {
        this.status = status;
        this.statusMessage = message;
        this.updatedAt = now;
    }

    void cancelRequested(String message, Instant now) {
        this.status = LiveOrderStatus.CANCEL_REQUESTED;
        this.statusMessage = message;
        this.updatedAt = now;
    }

    /**
     * Applies the broker's view of this order. Never moves backwards: a snapshot older than the last
     * one applied is ignored, a terminal order stays terminal, the filled quantity never decreases,
     * and a pending cancellation is kept until the broker reports a final state. Returns whether
     * anything changed (applying the same snapshot twice is a no-op).
     */
    boolean applyBroker(BrokerAdapter.BrokerOrder b, Instant now) {
        if (LiveOrderStatus.TERMINAL.contains(status) || !LiveOrderStatus.FROM_BROKER.contains(b.status())) {
            return false;
        }
        if (b.updatedAt() != null && brokerUpdatedAt != null && b.updatedAt().isBefore(brokerUpdatedAt)) {
            return false;
        }
        String next = b.status();
        if (LiveOrderStatus.CANCEL_REQUESTED.equals(status)
                && (LiveOrderStatus.OPEN.equals(next) || LiveOrderStatus.PARTIALLY_FILLED.equals(next))) {
            next = LiveOrderStatus.CANCEL_REQUESTED;
        }
        long filled = Math.max(filledQuantity, b.filledQuantity());
        BigDecimal avg = b.averagePrice() != null ? b.averagePrice() : averagePrice;
        boolean changed = !next.equals(status) || filled != filledQuantity || !java.util.Objects.equals(avg, averagePrice)
                || brokerOrderId == null || reviewReason != null;
        brokerOrderId = brokerOrderId == null ? b.brokerOrderId() : brokerOrderId;
        status = next;
        filledQuantity = filled;
        averagePrice = avg;
        reviewReason = null;
        if (b.updatedAt() != null) {
            brokerUpdatedAt = b.updatedAt();
        }
        if (changed) {
            statusMessage = b.message();
            updatedAt = now;
        }
        return changed;
    }

    /** The submission did not complete (crash or lost response): outcome unknown, never assumed failed. */
    void outcomeUnknown(String message, Instant now) {
        status = LiveOrderStatus.UNKNOWN;
        statusMessage = message;
        updatedAt = now;
    }

    boolean needsReview(String reason, String message, Instant now) {
        if (LiveOrderStatus.NEEDS_REVIEW.equals(status) && reason.equals(reviewReason)) {
            return false;
        }
        status = LiveOrderStatus.NEEDS_REVIEW;
        reviewReason = reason;
        statusMessage = message;
        updatedAt = now;
        return true;
    }

    void resolvedByUser(String message, Instant now) {
        status = LiveOrderStatus.RESOLVED_BY_USER;
        statusMessage = message;
        updatedAt = now;
    }

    void checked(Instant now) {
        reconcileChecks++;
        lastReconciledAt = now;
    }

    public OrderParams params() {
        return new OrderParams(exchange, symbol, side, orderType, quantity, limitPrice, triggerPrice);
    }
}
