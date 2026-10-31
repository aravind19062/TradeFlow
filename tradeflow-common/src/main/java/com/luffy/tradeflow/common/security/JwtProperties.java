package com.luffy.tradeflow.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How every service verifies access tokens. Tokens are RS256-signed by user-service only;
 * services hold public keys, so none of them can issue a user token.
 *
 * @param issuer     required {@code iss}
 * @param publicKeys resource pattern of the PEM public keys (file name = key id)
 * @param audience   {@code aud} this service accepts (default {@value #DEFAULT_AUDIENCE})
 */
@ConfigurationProperties("tradeflow.security.jwt")
public record JwtProperties(String issuer, String publicKeys, String audience) {

    public static final String DEFAULT_AUDIENCE = "tradeflow";
    /** Audience of short-lived step-up tokens that authorise live-trading actions. */
    public static final String LIVE_AUDIENCE = "tradeflow-live";

    public JwtProperties {
        issuer = issuer == null ? "tradeflow-user-service" : issuer;
        audience = audience == null || audience.isBlank() ? DEFAULT_AUDIENCE : audience;
    }
}
