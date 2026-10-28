package com.luffy.tradeflow.common.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Mints short-lived tokens with role SERVICE for background service-to-service calls
 * that have no end-user token to relay (e.g. reconciliation jobs). Endpoints under
 * /internal/** accept only this role; the API gateway never routes /internal/**.
 */
@Component
public class ServiceTokenProvider {

    public static final String ROLE_SERVICE = "SERVICE";
    private static final Duration TTL = Duration.ofMinutes(5);

    private final JwtEncoder encoder;
    private final JwtProperties properties;
    private final Clock clock;
    private final String serviceName;
    private volatile String cached;
    private volatile Instant cachedUntil = Instant.MIN;

    public ServiceTokenProvider(JwtEncoder encoder, JwtProperties properties, Clock clock,
                                @Value("${spring.application.name}") String serviceName) {
        this.encoder = encoder;
        this.properties = properties;
        this.clock = clock;
        this.serviceName = serviceName;
    }

    public String token() {
        Instant now = clock.instant();
        if (cached == null || now.isAfter(cachedUntil)) {
            JwtClaimsSet claims = JwtClaimsSet.builder()
                    .issuer(properties.issuer())
                    .subject("0")
                    .issuedAt(now)
                    .expiresAt(now.plus(TTL))
                    .claim("roles", List.of(ROLE_SERVICE))
                    .claim("service", serviceName)
                    .build();
            cached = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                    .getTokenValue();
            cachedUntil = now.plus(TTL).minusSeconds(60);
        }
        return cached;
    }
}
