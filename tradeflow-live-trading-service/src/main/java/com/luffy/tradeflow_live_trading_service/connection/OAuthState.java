package com.luffy.tradeflow_live_trading_service.connection;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/** One broker authorisation round trip: the state is stored hashed, the PKCE verifier encrypted. */
@Entity
@Table(name = "oauth_states")
@Getter(AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OAuthState {

    @Id
    @Column(name = "state_hash")
    private String stateHash;
    @Column(name = "user_id", nullable = false)
    private long userId;
    @Column(name = "broker_code", nullable = false)
    private String brokerCode;
    @Column(name = "connection_id", nullable = false)
    private UUID connectionId;
    @Column(name = "verifier_ct", nullable = false)
    private String verifierCiphertext;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
    @Column(name = "consumed_at")
    private Instant consumedAt;

    OAuthState(String stateHash, long userId, String brokerCode, UUID connectionId, String verifierCiphertext,
               Instant now, Instant expiresAt) {
        this.stateHash = stateHash;
        this.userId = userId;
        this.brokerCode = brokerCode;
        this.connectionId = connectionId;
        this.verifierCiphertext = verifierCiphertext;
        this.createdAt = now;
        this.expiresAt = expiresAt;
    }

    boolean usableBy(long user, Instant now) {
        return userId == user && consumedAt == null && now.isBefore(expiresAt);
    }

    void consume(Instant now) {
        this.consumedAt = now;
    }
}
