package com.luffy.tradeflow_user_service.security;

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

/** A user's TOTP seed, encrypted at rest (AES-256-GCM, bound to the user id). */
@Entity
@Table(name = "user_totp")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserTotp {

    public enum Status { PENDING, ENABLED }

    @Id
    @Column(name = "user_id")
    private Long userId;
    @Column(name = "secret_ciphertext", nullable = false)
    private String secretCiphertext;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "enabled_at")
    private Instant enabledAt;
    /** Highest accepted TOTP step; codes for this step or earlier are refused (no replay). */
    @Column(name = "last_used_step", nullable = false)
    private long lastUsedStep = -1;
    /** Optimistic lock: two requests cannot both consume the same code. */
    @Version
    private long version;

    UserTotp(long userId, String secretCiphertext, Instant now) {
        this.userId = userId;
        this.secretCiphertext = secretCiphertext;
        this.status = Status.PENDING;
        this.createdAt = now;
    }

    void restart(String secretCiphertext, Instant now) {
        this.secretCiphertext = secretCiphertext;
        this.status = Status.PENDING;
        this.createdAt = now;
        this.enabledAt = null;
        this.lastUsedStep = -1;
    }

    void enable(Instant now) {
        this.status = Status.ENABLED;
        this.enabledAt = now;
    }

    void accept(long step) {
        this.lastUsedStep = step;
    }

    public boolean enabled() {
        return status == Status.ENABLED;
    }
}
