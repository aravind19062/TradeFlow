package com.luffy.tradeflow_learning_service.readiness;

import java.util.EnumSet;
import java.util.Set;

/**
 * Readiness for live trading. Only learning-service moves a user between states, only along these
 * edges, and every move is an audit event. QUALIFIED is necessary for live trading but never
 * sufficient: the live service also requires a broker connection, MFA step-up, no self-exclusion
 * and no kill switch, and the broker applies its own KYC and eligibility rules.
 *
 * <pre>
 * NOT_STARTED ─academy started─▶ IN_PROGRESS ─modules + exercises done─▶ ASSESSMENT_PENDING
 * ASSESSMENT_PENDING ─final passed─▶ ACKNOWLEDGEMENT_PENDING ─risks acknowledged─▶ QUALIFIED
 * ASSESSMENT_PENDING ─max failed attempts─▶ REVIEW_REQUIRED ─reviewer─▶ ASSESSMENT_PENDING | IN_PROGRESS
 * QUALIFIED ─expiry / policy change / admin─▶ ACCESS_REVOKED ─requalification / admin reinstatement─▶ …
 * </pre>
 */
public enum ReadinessState {
    NOT_STARTED, IN_PROGRESS, ASSESSMENT_PENDING, REVIEW_REQUIRED, ACKNOWLEDGEMENT_PENDING, QUALIFIED, ACCESS_REVOKED;

    public Set<ReadinessState> allowedNext() {
        return switch (this) {
            case NOT_STARTED -> EnumSet.of(IN_PROGRESS, ACCESS_REVOKED);
            case IN_PROGRESS -> EnumSet.of(ASSESSMENT_PENDING, ACCESS_REVOKED);
            case ASSESSMENT_PENDING -> EnumSet.of(ACKNOWLEDGEMENT_PENDING, REVIEW_REQUIRED, IN_PROGRESS, ACCESS_REVOKED);
            case REVIEW_REQUIRED -> EnumSet.of(ASSESSMENT_PENDING, IN_PROGRESS, ACCESS_REVOKED);
            case ACKNOWLEDGEMENT_PENDING -> EnumSet.of(QUALIFIED, IN_PROGRESS, ACCESS_REVOKED);
            case QUALIFIED -> EnumSet.of(ACCESS_REVOKED);
            case ACCESS_REVOKED -> EnumSet.of(IN_PROGRESS, ASSESSMENT_PENDING);
        };
    }

    public boolean canMoveTo(ReadinessState next) {
        return allowedNext().contains(next);
    }
}
