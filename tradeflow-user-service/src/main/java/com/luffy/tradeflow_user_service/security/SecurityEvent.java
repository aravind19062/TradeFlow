package com.luffy.tradeflow_user_service.security;

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

/** Append-only audit record of an authentication or account-security action. */
@Entity
@Immutable
@Table(name = "security_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SecurityEvent {

    public enum Type {
        LOGIN_SUCCEEDED, LOGIN_FAILED, MFA_CHALLENGE_FAILED, RECOVERY_CODE_USED,
        MFA_ENROLLMENT_STARTED, MFA_ENABLED, MFA_DISABLED, RECOVERY_CODES_REGENERATED, MFA_RESET_BY_ADMIN,
        STEP_UP_SUCCEEDED, STEP_UP_FAILED, REFRESH_REUSE_DETECTED, SIGNED_OUT, SIGNED_OUT_EVERYWHERE,
        SERVICE_TOKEN_ISSUED, SERVICE_TOKEN_REFUSED,
        PROFILE_UPDATED, BANK_DETAILS_SAVED, BANK_DETAILS_REMOVED, BANK_DETAILS_CHANGE_FAILED,
        SESSION_REVOKED, PASSKEY_ADDED, PASSKEY_REMOVED, PASSKEY_CHANGE_FAILED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "user_id", nullable = false)
    private long userId;
    @Column(nullable = false)
    private String type;
    @Column(name = "actor_id")
    private Long actorId;
    private String detail;
    @Column(name = "client_ip")
    private String clientIp;
    @Column(name = "user_agent")
    private String userAgent;
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    SecurityEvent(long userId, Type type, Long actorId, String detail, String clientIp, String userAgent,
                  Instant at) {
        this.userId = userId;
        this.type = type.name();
        this.actorId = actorId;
        this.detail = truncate(detail, 500);
        this.clientIp = truncate(clientIp, 64);
        this.userAgent = truncate(userAgent, 255);
        this.occurredAt = at;
    }

    static String truncate(String s, int max) {
        return s == null ? null : s.substring(0, Math.min(s.length(), max));
    }
}
