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

/**
 * One link in a session's refresh-token chain. Only the SHA-256 of the token is stored. Each
 * use rotates it (the old row is marked used); presenting a used token again means it was
 * stolen or replayed, and the whole family (session) is revoked.
 */
@Entity
@Table(name = "refresh_tokens")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefreshToken {

    @Id
    private UUID id;
    @Column(name = "user_id", nullable = false)
    private long userId;
    @Column(name = "family_id", nullable = false)
    private UUID familyId;
    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;
    @Column(name = "token_version", nullable = false)
    private int tokenVersion;
    @Column(nullable = false)
    private String amr;
    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
    @Column(name = "session_expires_at", nullable = false)
    private Instant sessionExpiresAt;
    @Column(name = "used_at")
    private Instant usedAt;
    @Column(name = "revoked_at")
    private Instant revokedAt;
    @Column(name = "revoke_reason")
    private String revokeReason;
    @Column(name = "client_ip")
    private String clientIp;
    @Column(name = "user_agent")
    private String userAgent;

    RefreshToken(long userId, UUID familyId, String tokenHash, int tokenVersion, String amr, Instant now,
                 Instant expiresAt, Instant sessionExpiresAt, String clientIp, String userAgent) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.familyId = familyId;
        this.tokenHash = tokenHash;
        this.tokenVersion = tokenVersion;
        this.amr = amr;
        this.issuedAt = now;
        this.expiresAt = expiresAt.isAfter(sessionExpiresAt) ? sessionExpiresAt : expiresAt;
        this.sessionExpiresAt = sessionExpiresAt;
        this.clientIp = clientIp;
        this.userAgent = userAgent;
    }

    /** First use only: the reuse grace window is anchored to the first rotation and never extended. */
    void markUsed(Instant at) {
        if (usedAt == null) {
            this.usedAt = at;
        }
    }

    void revoke(Instant at, String reason) {
        if (revokedAt == null) {
            this.revokedAt = at;
            this.revokeReason = reason;
        }
    }
}
