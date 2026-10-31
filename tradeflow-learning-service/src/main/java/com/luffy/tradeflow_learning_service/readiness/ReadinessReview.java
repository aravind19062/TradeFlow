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

/** A reviewer's decision on a REVIEW_REQUIRED case (also recorded in the hash-chained audit log). */
@Entity
@Immutable
@Table(name = "readiness_reviews")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReadinessReview {

    public enum Decision { ALLOW_RETAKE, REQUIRE_REMEDIATION }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "user_id", nullable = false)
    private long userId;
    @Column(name = "reviewer_id", nullable = false)
    private long reviewerId;
    @Column(name = "reviewer_role", nullable = false)
    private String reviewerRole;
    @Column(nullable = false)
    private String decision;
    @Column(nullable = false)
    private String notes;
    @Column(name = "remediation_modules")
    private String remediationModules;
    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts;
    @Column(name = "decided_at", nullable = false)
    private Instant decidedAt;

    ReadinessReview(long userId, long reviewerId, String reviewerRole, Decision decision, String notes,
                    String remediationModules, int failedAttempts, Instant decidedAt) {
        this.userId = userId;
        this.reviewerId = reviewerId;
        this.reviewerRole = reviewerRole;
        this.decision = decision.name();
        this.notes = notes;
        this.remediationModules = remediationModules;
        this.failedAttempts = failedAttempts;
        this.decidedAt = decidedAt;
    }
}
