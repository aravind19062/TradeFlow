package com.luffy.tradeflow_portfolio_service.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.UUID;

/** Marks an order whose release was requested before any reservation existed. */
@Entity
@Table(name = "order_tombstones")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderTombstone implements Persistable<UUID> {

    @Id
    @Column(name = "order_id")
    private UUID orderId;

    @Column(nullable = false)
    private String reason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Transient
    private boolean newEntity = true;

    public OrderTombstone(UUID orderId, String reason, Instant createdAt) {
        this.orderId = orderId;
        this.reason = reason == null ? "released" : reason.substring(0, Math.min(reason.length(), 200));
        this.createdAt = createdAt;
    }

    @Override
    public UUID getId() {
        return orderId;
    }

    @Override
    public boolean isNew() {
        return newEntity;
    }
}
