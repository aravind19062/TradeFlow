package com.luffy.tradeflow_learning_service.policy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * Readiness requirements, versioned and immutable. All mandatory Academy modules are always
 * required; this records the configurable parts. Demo profit, trade count or volume are never
 * part of any policy.
 */
@Entity
@Immutable
@Table(name = "readiness_policies")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReadinessPolicy {

    @Id
    private int version;
    @Column(name = "module_pass_mark_pct", nullable = false)
    private int modulePassMarkPct;
    @Column(name = "final_pass_mark_pct", nullable = false)
    private int finalPassMarkPct;
    @Column(name = "final_retake_cooldown_hours", nullable = false)
    private int finalRetakeCooldownHours;
    @Column(name = "max_failed_final_attempts", nullable = false)
    private int maxFailedFinalAttempts;
    @Column(name = "qualification_validity_days", nullable = false)
    private int qualificationValidityDays;
    @Column(name = "required_exercises", nullable = false)
    private String requiredExercises;
    @Column(name = "acknowledgement_version", nullable = false)
    private int acknowledgementVersion;
    @Column(name = "requires_requalification", nullable = false)
    private boolean requiresRequalification;
    @Column(name = "effective_from", nullable = false)
    private Instant effectiveFrom;
    @Column(name = "created_by")
    private Long createdBy;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(nullable = false)
    private String notes;

    public ReadinessPolicy(int version, int modulePassMarkPct, int finalPassMarkPct, int finalRetakeCooldownHours,
                           int maxFailedFinalAttempts, int qualificationValidityDays, List<String> requiredExercises,
                           int acknowledgementVersion, boolean requiresRequalification, Instant effectiveFrom,
                           Long createdBy, Instant createdAt, String notes) {
        this.version = version;
        this.modulePassMarkPct = modulePassMarkPct;
        this.finalPassMarkPct = finalPassMarkPct;
        this.finalRetakeCooldownHours = finalRetakeCooldownHours;
        this.maxFailedFinalAttempts = maxFailedFinalAttempts;
        this.qualificationValidityDays = qualificationValidityDays;
        this.requiredExercises = String.join(",", requiredExercises);
        this.acknowledgementVersion = acknowledgementVersion;
        this.requiresRequalification = requiresRequalification;
        this.effectiveFrom = effectiveFrom;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
        this.notes = notes;
    }

    public List<String> requiredExerciseSlugs() {
        return requiredExercises.isBlank() ? List.of() : Arrays.asList(requiredExercises.split(","));
    }

    public Duration finalRetakeCooldown() {
        return Duration.ofHours(finalRetakeCooldownHours);
    }

    public Duration qualificationValidity() {
        return Duration.ofDays(qualificationValidityDays);
    }
}
