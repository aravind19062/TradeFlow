package com.luffy.tradeflow.common.testsupport;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Mints real RS256 tokens with the TEST-ONLY key in {@code jwt-test/}, so tests exercise the
 * production decoder (public key ring, issuer, audience, expiry). Services in tests are
 * configured with {@link #PUBLIC_KEYS}.
 */
public final class TestJwts {

    public static final String ISSUER = "tradeflow-user-service";
    /** Key id of the test key: first 16 hex chars of SHA-256 of its DER public key (see KeyIds). */
    public static final String KEY_ID = "b7efda908d8310ec";
    /** Value for tradeflow.security.jwt.public-keys in test profiles. */
    public static final String PUBLIC_KEYS = "classpath*:jwt-test/public/*.pem";
    /** Value for user-service's tradeflow.security.jwt.signing-key in tests. */
    public static final String SIGNING_KEY = "classpath:jwt-test/test-signing-key.pem";

    private static final NimbusJwtEncoder ENCODER = encoder("jwt-test/test-signing-key.pem", KEY_ID);
    /** Signs with a key that is NOT in the public key ring. */
    private static final NimbusJwtEncoder UNTRUSTED = encoder("jwt-test/untrusted-signing-key.pem", KEY_ID);

    private TestJwts() {
    }

    public static String user(long userId) {
        return token(userId, "USER");
    }

    public static String admin(long userId) {
        return token(userId, "ADMIN");
    }

    /** A service-to-service token (role SERVICE), as issued by user-service's service-token endpoint. */
    public static String service() {
        return sign(ENCODER, claims("service:test", List.of("SERVICE"), "tradeflow", 300, Map.of()));
    }

    public static String token(long userId, String role) {
        return sign(ENCODER, claims(String.valueOf(userId), List.of(role), "tradeflow", 3600,
                Map.of("email", "user" + userId + "@test.dev")));
    }

    /** Same claims as {@link #user(long)} but signed with a key the services do not trust. */
    public static String untrustedUser(long userId) {
        return sign(UNTRUSTED, claims(String.valueOf(userId), List.of("USER"), "tradeflow", 3600, Map.of()));
    }

    public static String expiredUser(long userId) {
        Instant past = Instant.now().minusSeconds(7200);
        return ENCODER.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).keyId(KEY_ID).build(),
                JwtClaimsSet.builder().issuer(ISSUER).subject(String.valueOf(userId)).audience(List.of("tradeflow"))
                        .issuedAt(past).expiresAt(past.plusSeconds(60)).claim("roles", List.of("USER")).build()))
                .getTokenValue();
    }

    public static String withAudience(long userId, String audience, Map<String, Object> extra) {
        return withAudience(userId, "USER", audience, extra);
    }

    public static String withAudience(long userId, String role, String audience, Map<String, Object> extra) {
        return sign(ENCODER, claims(String.valueOf(userId), List.of(role), audience, 600, extra));
    }

    public static String bearer(String token) {
        return "Bearer " + token;
    }

    private static JwtClaimsSet claims(String subject, List<String> roles, String audience, long ttlSeconds,
                                       Map<String, Object> extra) {
        Instant now = Instant.now();
        JwtClaimsSet.Builder b = JwtClaimsSet.builder().issuer(ISSUER).subject(subject).audience(List.of(audience))
                .issuedAt(now).expiresAt(now.plusSeconds(ttlSeconds)).claim("roles", roles);
        extra.forEach(b::claim);
        return b.build();
    }

    private static String sign(NimbusJwtEncoder encoder, JwtClaimsSet claims) {
        return encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.RS256).keyId(KEY_ID).build(), claims)).getTokenValue();
    }

    private static NimbusJwtEncoder encoder(String resource, String keyId) {
        try (InputStream in = TestJwts.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing test key " + resource);
            }
            String pem = new String(in.readAllBytes(), StandardCharsets.US_ASCII);
            byte[] der = Base64.getMimeDecoder().decode(pem.replaceAll("-----(BEGIN|END) PRIVATE KEY-----", "").trim());
            KeyFactory kf = KeyFactory.getInstance("RSA");
            RSAPrivateCrtKey priv = (RSAPrivateCrtKey) kf.generatePrivate(new PKCS8EncodedKeySpec(der));
            RSAPublicKey pub = (RSAPublicKey) kf.generatePublic(
                    new RSAPublicKeySpec(priv.getModulus(), priv.getPublicExponent()));
            RSAKey jwk = new RSAKey.Builder(pub).privateKey(priv).keyID(keyId).build();
            return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(jwk)));
        } catch (IOException | java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
