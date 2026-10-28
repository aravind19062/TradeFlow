package com.luffy.tradeflow.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * HS256 settings shared by the issuer (user-service) and every resource server.
 * The secret must come from the environment ({@code TRADEFLOW_JWT_SECRET}) outside
 * local development.
 */
@ConfigurationProperties("tradeflow.security.jwt")
public record JwtProperties(String secret, String issuer, Duration accessTokenTtl) {

    public JwtProperties {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("Refusing to start: TRADEFLOW_JWT_SECRET (tradeflow.security.jwt.secret) "
                    + "must be set to a private value of at least 32 bytes, e.g. `openssl rand -base64 48`. "
                    + "For local development activate the 'local' profile instead.");
        }
        issuer = issuer == null ? "tradeflow" : issuer;
        accessTokenTtl = accessTokenTtl == null ? Duration.ofHours(1) : accessTokenTtl;
    }

    /** Never print the signing secret (records include all fields in toString by default). */
    @Override
    public String toString() {
        return "JwtProperties[secret=<redacted>, issuer=" + issuer + ", accessTokenTtl=" + accessTokenTtl + "]";
    }
}
