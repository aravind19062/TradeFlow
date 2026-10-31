package com.luffy.tradeflow_live_trading_service.orders;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Issued by a preview; one use, short-lived, bound to the user and the exact order parameters. */
@Entity
@Table(name = "order_confirmations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderConfirmation {

    @Id
    private UUID id;
    @Column(name = "user_id", nullable = false)
    private long userId;
    @Column(name = "params_sha256", nullable = false)
    private String paramsSha256;
    @Column(name = "order_value", nullable = false)
    private BigDecimal orderValue;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
    @Column(name = "consumed_at")
    private Instant consumedAt;

    OrderConfirmation(long userId, String paramsSha256, BigDecimal orderValue, Instant now, Instant expiresAt) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.paramsSha256 = paramsSha256;
        this.orderValue = orderValue;
        this.createdAt = now;
        this.expiresAt = expiresAt;
    }

    boolean usable(Instant now) {
        return consumedAt == null && now.isBefore(expiresAt);
    }

    void consume(Instant now) {
        this.consumedAt = now;
    }
}
