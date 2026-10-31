package com.luffy.tradeflow_live_trading_service.platform;

import java.time.Instant;
import java.util.List;

/**
 * Synchronous checks against the platform services, made before every live action. Any failure to
 * get an answer must be treated by callers as "refuse" (fail closed); implementations throw.
 */
public interface PlatformChecks {

    /** From learning-service: whether readiness currently permits live trading. */
    Eligibility eligibility(long userId);

    /** From user-service: whether the user's tokens are still current. */
    SessionState session(long userId);

    record Eligibility(long userId, String state, boolean eligible, List<String> reasons,
                       Integer qualifiedPolicyVersion, Instant qualifiedAt, Instant expiresAt,
                       Instant selfExcludedUntil, Instant checkedAt) {
    }

    record SessionState(long userId, boolean active, int tokenVersion, boolean mfaEnabled) {
    }
}
