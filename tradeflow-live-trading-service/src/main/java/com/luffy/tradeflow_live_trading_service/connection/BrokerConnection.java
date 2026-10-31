package com.luffy.tradeflow_live_trading_service.connection;

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

/** A user's authorised link to their broker account (tokens live in BrokerCredential, encrypted). */
@Entity
@Table(name = "broker_connections")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BrokerConnection {

    public enum Status { PENDING, ACTIVE, EXPIRED, DISCONNECTED, ERROR }

    @Id
    private UUID id;
    @Column(name = "user_id", nullable = false)
    private long userId;
    @Column(name = "broker_code", nullable = false)
    private String brokerCode;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;
    @Column(name = "broker_account_ref")
    private String brokerAccountRef;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "connected_at")
    private Instant connectedAt;
    @Column(name = "expires_at")
    private Instant expiresAt;
    @Column(name = "disconnected_at")
    private Instant disconnectedAt;
    @Version
    private long version;

    public BrokerConnection(long userId, String brokerCode, Instant now) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.brokerCode = brokerCode;
        this.status = Status.PENDING;
        this.createdAt = now;
    }

    public void activate(String accountRef, Instant now, Instant expiresAt) {
        this.status = Status.ACTIVE;
        this.brokerAccountRef = accountRef;
        this.connectedAt = now;
        this.expiresAt = expiresAt;
    }

    public void disconnect(Instant now) {
        this.status = Status.DISCONNECTED;
        this.disconnectedAt = now;
    }

    public boolean usable(Instant now) {
        return status == Status.ACTIVE && (expiresAt == null || now.isBefore(expiresAt));
    }
}
