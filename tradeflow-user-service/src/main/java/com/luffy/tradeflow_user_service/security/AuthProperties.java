package com.luffy.tradeflow_user_service.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Map;

/**
 * Session and token policy (tradeflow.security.auth.*).
 *
 * @param signingKey            PEM (PKCS#8) RSA private key; only user-service has it
 * @param accessTokenTtl        bearer access token lifetime (short: revocation takes effect within it)
 * @param stepUpTokenTtl        lifetime of the password+TOTP step-up token that authorises live trading
 * @param serviceTokenTtl       lifetime of SERVICE tokens issued to other services
 * @param refreshIdleTtl        a refresh token unused for this long expires (idle timeout)
 * @param sessionMaxTtl         absolute session lifetime, however often it is refreshed
 * @param mfaChallengeTtl       time to enter the TOTP code after the password at login
 * @param stepUpHoldAfterMfaReset live step-up is refused this long after an administrator MFA reset
 * @param cookieSecure          send the refresh cookie only over HTTPS (false only for local http)
 * @param serviceClients        service name to client secret, for /internal/auth/service-token
 */
@ConfigurationProperties("tradeflow.security.auth")
public record AuthProperties(String signingKey, Duration accessTokenTtl, Duration stepUpTokenTtl,
                             Duration serviceTokenTtl, Duration refreshIdleTtl, Duration sessionMaxTtl,
                             Duration mfaChallengeTtl, Duration stepUpHoldAfterMfaReset, Boolean cookieSecure,
                             Map<String, String> serviceClients) {

    public AuthProperties {
        accessTokenTtl = or(accessTokenTtl, Duration.ofMinutes(15));
        stepUpTokenTtl = or(stepUpTokenTtl, Duration.ofMinutes(10));
        serviceTokenTtl = or(serviceTokenTtl, Duration.ofMinutes(5));
        refreshIdleTtl = or(refreshIdleTtl, Duration.ofHours(8));
        sessionMaxTtl = or(sessionMaxTtl, Duration.ofHours(24));
        mfaChallengeTtl = or(mfaChallengeTtl, Duration.ofMinutes(5));
        stepUpHoldAfterMfaReset = or(stepUpHoldAfterMfaReset, Duration.ofHours(24));
        cookieSecure = cookieSecure == null || cookieSecure;
        serviceClients = serviceClients == null ? Map.of() : Map.copyOf(serviceClients);
        if (accessTokenTtl.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalStateException("tradeflow.security.auth.access-token-ttl must be at most 1 hour");
        }
        if (stepUpTokenTtl.compareTo(Duration.ofMinutes(15)) > 0) {
            throw new IllegalStateException("tradeflow.security.auth.step-up-token-ttl must be at most 15 minutes");
        }
    }

    private static Duration or(Duration d, Duration fallback) {
        return d == null ? fallback : d;
    }

    /** Never print client secrets. */
    @Override
    public String toString() {
        return "AuthProperties[accessTokenTtl=" + accessTokenTtl + ", stepUpTokenTtl=" + stepUpTokenTtl
                + ", refreshIdleTtl=" + refreshIdleTtl + ", sessionMaxTtl=" + sessionMaxTtl
                + ", serviceClients=" + serviceClients.keySet() + "]";
    }
}
