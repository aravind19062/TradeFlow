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
            throw new IllegalStateException("tradeflow.security.jwt.secret (env TRADEFLOW_JWT_SECRET) must be set "
                    + "and at least 32 bytes long for HS256");
        }
        issuer = issuer == null ? "tradeflow" : issuer;
        accessTokenTtl = accessTokenTtl == null ? Duration.ofHours(1) : accessTokenTtl;
    }
}
