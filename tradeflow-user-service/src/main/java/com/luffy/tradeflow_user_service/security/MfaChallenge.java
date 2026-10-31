package com.luffy.tradeflow_user_service.security;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/** Issued after a correct password when the account has MFA; exchanged for tokens with a valid code. */
@Entity
@Table(name = "mfa_challenges")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MfaChallenge {

    public static final int MAX_ATTEMPTS = 5;

    @Id
    private UUID id;
    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;
    @Column(name = "user_id", nullable = false)
    private long userId;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
    @Column(nullable = false)
    private int attempts;
    @Column(name = "consumed_at")
    private Instant consumedAt;

    MfaChallenge(String tokenHash, long userId, Instant now, Instant expiresAt) {
        this.id = UUID.randomUUID();
        this.tokenHash = tokenHash;
        this.userId = userId;
        this.createdAt = now;
        this.expiresAt = expiresAt;
    }

    boolean usable(Instant now) {
        return consumedAt == null && now.isBefore(expiresAt) && attempts < MAX_ATTEMPTS;
    }

    void failedAttempt() {
        attempts++;
    }

    void consume(Instant now) {
        consumedAt = now;
    }
}
