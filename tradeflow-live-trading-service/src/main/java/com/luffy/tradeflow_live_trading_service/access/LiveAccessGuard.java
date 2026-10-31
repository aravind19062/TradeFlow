package com.luffy.tradeflow_live_trading_service.access;

import com.luffy.tradeflow.common.security.JwtProperties;
import com.luffy.tradeflow.common.web.ApiException;
import com.luffy.tradeflow_live_trading_service.audit.LiveAuditService;
import com.luffy.tradeflow_live_trading_service.config.LiveProperties;
import com.luffy.tradeflow_live_trading_service.platform.PlatformChecks;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Independent authorisation for every live action, in this service (the gateway's checks are a
 * first line only). Fails closed: if a check cannot be completed the action is refused. Every
 * refusal is audited with its reason.
 *
 * <ol>
 *   <li>the caller is a user (not a service token);</li>
 *   <li>step-up: audience {@code tradeflow-live}, amr includes pwd and otp, auth_time within the
 *       configured maximum age;</li>
 *   <li>session: account active, token version current (so "sign out everywhere", a password change
 *       or an MFA reset invalidates step-up tokens at once), MFA still enabled;</li>
 *   <li>no global or per-user kill switch (for actions that open new exposure);</li>
 *   <li>readiness: learning-service says eligible right now (for actions that open new exposure).</li>
 * </ol>
 */
@Component
public class LiveAccessGuard {

    private final PlatformChecks platform;
    private final KillSwitchService killSwitches;
    private final LiveAuditService audit;
    private final LiveProperties properties;
    private final Clock clock;

    public LiveAccessGuard(PlatformChecks platform, KillSwitchService killSwitches, LiveAuditService audit,
                           LiveProperties properties, Clock clock) {
        this.platform = platform;
        this.killSwitches = killSwitches;
        this.audit = audit;
        this.properties = properties;
        this.clock = clock;
    }

    /** Returns the authorised user id, or throws (after auditing the refusal). */
    public long authorize(Jwt jwt, LiveAction action) {
        long userId = userId(jwt);
        try {
            check(jwt, userId, action);
            return userId;
        } catch (ApiException refused) {
            audit.record(userId, action.name(), LiveAuditService.Outcome.REFUSED, refused.getCode(), userId);
            throw refused;
        }
    }

    public static long userId(Jwt jwt) {
        try {
            return Long.parseLong(jwt.getSubject());
        } catch (RuntimeException e) {
            throw new ApiException(HttpStatus.FORBIDDEN, "NOT_A_USER", "Live trading is only available to user accounts");
        }
    }

    private void check(Jwt jwt, long userId, LiveAction action) {
        if (action.stepUp) {
            requireStepUp(jwt);
        }
        PlatformChecks.SessionState session = platform.session(userId);
        Number tv = jwt.getClaim("tv");
        if (!session.active() || tv == null || tv.intValue() != session.tokenVersion()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "SESSION_REVOKED",
                    "This session is no longer valid. Sign in again.");
        }
        if (!session.mfaEnabled()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "MFA_REQUIRED",
                    "Two-factor authentication must be enabled for live trading.");
        }
        if (action.notHalted) {
            String halt = killSwitches.haltReason(userId);
            if (halt != null) {
                throw new ApiException(HttpStatus.LOCKED, "LIVE_TRADING_HALTED", halt);
            }
        }
        if (action.readiness) {
            PlatformChecks.Eligibility e = platform.eligibility(userId);
            if (!e.eligible() || e.userId() != userId) {
                List<String> reasons = e.reasons() == null ? List.of() : e.reasons();
                throw new ApiException(HttpStatus.FORBIDDEN, "NOT_ELIGIBLE", "Live trading is not available: "
                        + (reasons.isEmpty() ? "readiness is " + e.state() : String.join("; ", reasons)));
            }
        }
    }

    private void requireStepUp(Jwt jwt) {
        List<String> aud = jwt.getAudience();
        List<String> amr = jwt.getClaimAsStringList("amr");
        Instant authTime = authTime(jwt);
        Duration maxAge = properties.stepUpMaxAge();
        boolean ok = aud != null && aud.contains(JwtProperties.LIVE_AUDIENCE)
                && amr != null && amr.contains("pwd") && amr.contains("otp")
                && authTime != null && !authTime.isAfter(clock.instant().plusSeconds(30))
                && Duration.between(authTime, clock.instant()).compareTo(maxAge) <= 0;
        if (!ok) {
            throw new ApiException(HttpStatus.FORBIDDEN, "STEP_UP_REQUIRED",
                    "Confirm with your password and authenticator code to continue.");
        }
    }

    private static Instant authTime(Jwt jwt) {
        Object v = jwt.getClaim("auth_time");
        if (v instanceof Instant i) {
            return i;
        }
        if (v instanceof Number n) {
            return Instant.ofEpochSecond(n.longValue());
        }
        return null;
    }
}
