package com.luffy.tradeflow_live_trading_service.access;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;

/** Halts new live orders platform-wide (GLOBAL, user 0) or for one user. Never cancels existing orders. */
@Entity
@Table(name = "kill_switches")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class KillSwitch {

    public enum Scope { GLOBAL, USER }

    @Embeddable
    public record Key(@Column(name = "scope") String scope, @Column(name = "user_id") long userId) implements Serializable {
    }

    @EmbeddedId
    private Key id;
    @Column(nullable = false)
    private boolean enabled;
    @Column(nullable = false)
    private String reason;
    @Column(name = "actor_id", nullable = false)
    private long actorId;
    @Column(name = "changed_at", nullable = false)
    private Instant changedAt;

    KillSwitch(Key id) {
        this.id = id;
    }

    void set(boolean enabled, String reason, long actorId, Instant now) {
        this.enabled = enabled;
        this.reason = reason;
        this.actorId = actorId;
        this.changedAt = now;
    }
}
