package com.luffy.tradeflow_order_service.saga;

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

import java.time.Instant;
import java.util.UUID;

/** Progress record for one order's workflow. Updated in the same transaction as the order. */
@Entity
@Table(name = "order_sagas")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderSaga {

    @Id
    @Column(name = "order_id")
    private UUID orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SagaState state;

    @Column(name = "deadline_at")
    private Instant deadlineAt;

    @Column(name = "redrive_count", nullable = false)
    private int redriveCount;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    public OrderSaga(UUID orderId, Instant deadline, Instant now) {
        this.orderId = orderId;
        this.state = SagaState.AWAITING_RESERVATION;
        this.deadlineAt = deadline;
        this.createdAt = now;
        this.updatedAt = now;
    }

    void moveTo(SagaState next, Instant deadline, Instant now) {
        if (next != state) {
            this.redriveCount = 0;
        }
        this.state = next;
        this.deadlineAt = next.isInFlight() ? deadline : null;
        this.updatedAt = now;
    }

    void redriven(Instant nextDeadline, Instant now) {
        this.redriveCount++;
        this.deadlineAt = nextDeadline;
        this.updatedAt = now;
    }

    void fail(SagaState failedState, String error, Instant now) {
        this.state = failedState;
        this.lastError = error == null ? null : error.substring(0, Math.min(error.length(), 500));
        this.deadlineAt = null;
        this.updatedAt = now;
    }

    void note(String error) {
        this.lastError = error == null ? null : error.substring(0, Math.min(error.length(), 500));
    }
}
