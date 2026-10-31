package com.luffy.tradeflow_learning_service.readiness;

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

import java.time.Instant;

/** One audited readiness transition (append-only, hash-chained per user). */
@Entity
@Immutable
@Table(name = "readiness_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReadinessEvent {

    public enum ActorType { USER, SYSTEM, REVIEWER, ADMIN }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "user_id", nullable = false)
    private long userId;
    @Column(nullable = false)
    private int seq;
    @Column(name = "from_state")
    private String fromState;
    @Column(name = "to_state", nullable = false)
    private String toState;
    @Column(name = "reason_code", nullable = false)
    private String reasonCode;
    @Column(name = "actor_type", nullable = false)
    private String actorType;
    @Column(name = "actor_id")
    private Long actorId;
    @Column(name = "policy_version", nullable = false)
    private int policyVersion;
    private String detail;
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;
    @Column(name = "prev_hash", nullable = false)
    private String prevHash;
    @Column(nullable = false)
    private String hash;

    ReadinessEvent(long userId, int seq, String fromState, String toState, String reasonCode, ActorType actorType,
                   Long actorId, int policyVersion, String detail, Instant occurredAt, String prevHash) {
        this.userId = userId;
        this.seq = seq;
        this.fromState = fromState;
        this.toState = toState;
        this.reasonCode = reasonCode;
        this.actorType = actorType.name();
        this.actorId = actorId;
        this.policyVersion = policyVersion;
        this.detail = detail == null ? null : detail.substring(0, Math.min(detail.length(), 1000));
        // Stored with microsecond precision; hash exactly what is stored.
        this.occurredAt = occurredAt.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        this.prevHash = prevHash;
        this.hash = computeHash();
    }

    /** SHA-256 over the previous hash and every field of this event (in a fixed, unambiguous form). */
    public String computeHash() {
        String material = String.join("\u001f", prevHash, String.valueOf(userId), String.valueOf(seq),
                String.valueOf(fromState), toState, reasonCode, actorType, String.valueOf(actorId),
                String.valueOf(policyVersion), String.valueOf(detail),
                String.valueOf(occurredAt.truncatedTo(java.time.temporal.ChronoUnit.MICROS)));
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
