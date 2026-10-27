package com.luffy.tradeflow_portfolio_service.domain;

import com.luffy.tradeflow.common.events.Events.OrderSide;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Funds (BUY) or shares (SELL) held for one order until it fills or is cancelled. */
@Entity
@Table(name = "reservations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Reservation {

    public enum Status { ACTIVE, SETTLED, RELEASED }

    @Id
    @Column(name = "order_id")
    private UUID orderId;

    @Column(name = "user_id", nullable = false)
    private long userId;

    @Column(nullable = false)
    private String symbol;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderSide side;

    @Column(nullable = false)
    private long quantity;

    @Column(name = "cash_amount", nullable = false)
    private BigDecimal cashAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Version
    private long version;

    public Reservation(UUID orderId, long userId, String symbol, OrderSide side, long quantity,
                       BigDecimal cashAmount, Instant now) {
        this.orderId = orderId;
        this.userId = userId;
        this.symbol = symbol;
        this.side = side;
        this.quantity = quantity;
        this.cashAmount = cashAmount;
        this.status = Status.ACTIVE;
        this.createdAt = now;
    }

    public boolean isActive() {
        return status == Status.ACTIVE;
    }

    public void settle(Instant now) {
        resolve(Status.SETTLED, now);
    }

    public void release(Instant now) {
        resolve(Status.RELEASED, now);
    }

    private void resolve(Status next, Instant now) {
        if (status != Status.ACTIVE) {
            throw new IllegalStateException("Reservation " + orderId + " is already " + status);
        }
        status = next;
        resolvedAt = now;
    }
}
