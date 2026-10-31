package com.luffy.tradeflow_live_trading_service.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;

/** One live action, allowed or refused. Append-only (a PostgreSQL trigger refuses UPDATE/DELETE), hash-chained per user. */
@Entity
@Immutable
@Table(name = "live_audit_log")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LiveAuditEvent {

    public static final String GENESIS = "0".repeat(64);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "user_id", nullable = false)
    private long userId;
    @Column(nullable = false)
    private int seq;
    @Column(nullable = false)
    private String action;
    @Column(nullable = false)
    private String outcome;
    private String detail;
    @Column(name = "actor_id", nullable = false)
    private long actorId;
    @Column(name = "client_ip")
    private String clientIp;
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;
    @Column(name = "prev_hash", nullable = false)
    private String prevHash;
    @Column(nullable = false)
    private String hash;

    LiveAuditEvent(long userId, int seq, String action, String outcome, String detail, long actorId, String clientIp,
                   Instant occurredAt, String prevHash) {
        this.userId = userId;
        this.seq = seq;
        this.action = action;
        this.outcome = outcome;
        this.detail = detail == null ? null : detail.substring(0, Math.min(detail.length(), 1000));
        this.actorId = actorId;
        this.clientIp = clientIp == null ? null : clientIp.substring(0, Math.min(clientIp.length(), 64));
        this.occurredAt = occurredAt.truncatedTo(ChronoUnit.MICROS);
        this.prevHash = prevHash;
        this.hash = computeHash();
    }

    public String computeHash() {
        String material = String.join("\u001f", prevHash, String.valueOf(userId), String.valueOf(seq), action, outcome,
                String.valueOf(detail), String.valueOf(actorId), String.valueOf(clientIp),
                String.valueOf(occurredAt.truncatedTo(ChronoUnit.MICROS)));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
