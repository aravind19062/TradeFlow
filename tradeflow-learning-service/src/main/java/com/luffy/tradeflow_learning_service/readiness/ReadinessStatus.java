package com.luffy.tradeflow_learning_service.readiness;

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
import java.util.Arrays;
import java.util.List;

/** Current readiness of one user. Changed only through ReadinessService (which audits every move). */
@Entity
@Table(name = "readiness_status")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReadinessStatus {

    @Id
    @Column(name = "user_id")
    private Long userId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReadinessState state;
    @Column(name = "state_reason", nullable = false)
    private String stateReason;
    @Column(name = "policy_version", nullable = false)
    private int policyVersion;
    /** Start of the current final-assessment cycle (failed attempts are counted from here). */
    @Column(name = "cycle_started_at")
    private Instant cycleStartedAt;
    @Column(name = "qualified_at")
    private Instant qualifiedAt;
    @Column(name = "qualified_policy_version")
    private Integer qualifiedPolicyVersion;
    @Column(name = "expires_at")
    private Instant expiresAt;
    @Column(name = "remediation_modules")
    private String remediationModules;
    @Column(name = "remediation_since")
    private Instant remediationSince;
    @Column(name = "self_excluded_until")
    private Instant selfExcludedUntil;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Version
    private long version;

    ReadinessStatus(long userId, int policyVersion, Instant now) {
        this.userId = userId;
        this.state = ReadinessState.NOT_STARTED;
        this.stateReason = "CREATED";
        this.policyVersion = policyVersion;
        this.updatedAt = now;
    }

    void moveTo(ReadinessState next, String reason, int policyVersion, Instant now) {
        this.state = next;
        this.stateReason = reason;
        this.policyVersion = policyVersion;
        this.updatedAt = now;
    }

    void startCycle(Instant now) {
        this.cycleStartedAt = now;
    }

    void qualify(Instant now, int policyVersion, Instant expiresAt) {
        this.qualifiedAt = now;
        this.qualifiedPolicyVersion = policyVersion;
        this.expiresAt = expiresAt;
    }

    void clearQualification() {
        this.qualifiedAt = null;
        this.qualifiedPolicyVersion = null;
        this.expiresAt = null;
    }

    void requireRemediation(List<String> modules, Instant since) {
        this.remediationModules = modules.isEmpty() ? null : String.join(",", modules);
        this.remediationSince = modules.isEmpty() ? null : since;
    }

    public List<String> remediationModuleList() {
        return remediationModules == null || remediationModules.isBlank() ? List.of()
                : Arrays.asList(remediationModules.split(","));
    }

    void excludeUntil(Instant until) {
        this.selfExcludedUntil = until;
    }
}
