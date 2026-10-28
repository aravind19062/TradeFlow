package com.luffy.tradeflow.common.testsupport;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

/** Mints real HS256 tokens with the test-profile secret, so tests exercise the production JWT decoder. */
public final class TestJwts {

    public static final String SECRET = "test-only-jwt-secret-0123456789-abcdefghij";
    public static final String ISSUER = "tradeflow-user-service";

    private static final NimbusJwtEncoder ENCODER = new NimbusJwtEncoder(new ImmutableSecret<>(
            new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256")));

    private TestJwts() {
    }

    public static String user(long userId) {
        return token(userId, "USER");
    }

    public static String admin(long userId) {
        return token(userId, "ADMIN");
    }

    /** A service-to-service token (role SERVICE), as minted by ServiceTokenProvider. */
    public static String service() {
        return token(0, "SERVICE");
    }

    public static String token(long userId, String role) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject(String.valueOf(userId))
                .issuedAt(now)
                .expiresAt(now.plusSeconds(3600))
                .claim("email", "user" + userId + "@test.dev")
                .claim("roles", List.of(role))
                .build();
        return ENCODER.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }

    public static String bearer(String token) {
        return "Bearer " + token;
    }
}
