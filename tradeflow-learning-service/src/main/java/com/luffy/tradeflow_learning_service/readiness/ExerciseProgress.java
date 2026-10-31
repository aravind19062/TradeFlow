package com.luffy.tradeflow_learning_service.readiness;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;

/**
 * A practical exercise the user has started; completed only from DEMO order events that happened
 * after the start (never from a client claim, never from profit or trade counts).
 */
@Entity
@Table(name = "exercise_progress")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ExerciseProgress {

    @Embeddable
    @Getter
    @EqualsAndHashCode
    @NoArgsConstructor(access = AccessLevel.PROTECTED)
    public static class Key implements Serializable {
        @Column(name = "user_id")
        private long userId;
        private String slug;

        public Key(long userId, String slug) {
            this.userId = userId;
            this.slug = slug;
        }
    }

    @EmbeddedId
    private Key id;
    @Column(name = "started_at", nullable = false)
    private Instant startedAt;
    @Column(name = "completed_at")
    private Instant completedAt;
    private String evidence;

    ExerciseProgress(Key id, Instant startedAt) {
        this.id = id;
        this.startedAt = startedAt;
    }

    boolean complete(String evidence, Instant at) {
        if (completedAt != null) {
            return false;
        }
        this.completedAt = at;
        this.evidence = evidence;
        return true;
    }
}
