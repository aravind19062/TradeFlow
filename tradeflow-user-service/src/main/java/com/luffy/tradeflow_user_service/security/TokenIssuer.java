package com.luffy.tradeflow_user_service.security;

import com.luffy.tradeflow.common.security.JwtProperties;
import com.luffy.tradeflow.common.security.KeyIds;
import com.luffy.tradeflow_user_service.entity.User;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * The only component in TradeFlow that can sign tokens (RS256, private key held by user-service
 * alone). Three kinds:
 * <ul>
 *   <li><b>access</b>: aud {@code tradeflow}, 15 min, for every API call;</li>
 *   <li><b>step-up</b>: aud {@code tradeflow-live}, 10 min, issued only after password + TOTP,
 *       required by live-trading actions;</li>
 *   <li><b>service</b>: role SERVICE, 5 min, for authenticated service clients' internal calls.</li>
 * </ul>
 */
@Component
public class TokenIssuer {

    private final NimbusJwtEncoder encoder;
    private final String keyId;
    private final JwtProperties jwt;
    private final AuthProperties auth;
    private final Clock clock;

    public TokenIssuer(JwtProperties jwt, AuthProperties auth, Clock clock) {
        this.jwt = jwt;
        this.auth = auth;
        this.clock = clock;
        RSAKey key = loadSigningKey(auth.signingKey());
        this.keyId = key.getKeyID();
        this.encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
    }

    public record Issued(String token, Instant expiresAt) {
    }

    public Issued access(User user, UUID sessionId, List<String> amr, Instant authTime) {
        return sign(builder(String.valueOf(user.getId()), JwtProperties.DEFAULT_AUDIENCE, auth.accessTokenTtl())
                .claim("email", user.getMailId())
                .claim("name", user.getName())
                .claim("roles", List.of(user.getRole()))
                .claim("tv", user.getTokenVersion())
                .claim("sid", sessionId.toString())
                .claim("amr", amr)
                .claim("auth_time", authTime.getEpochSecond()), auth.accessTokenTtl());
    }

    /** Only after re-entering the password and a TOTP code; amr is always [pwd, otp]. */
    public Issued stepUp(User user, UUID sessionId) {
        Instant now = clock.instant();
        return sign(builder(String.valueOf(user.getId()), JwtProperties.LIVE_AUDIENCE, auth.stepUpTokenTtl())
                .claim("roles", List.of(user.getRole()))
                .claim("tv", user.getTokenVersion())
                .claim("sid", sessionId.toString())
                .claim("amr", List.of("pwd", "otp"))
                .claim("auth_time", now.getEpochSecond())
                .claim("scope", "live"), auth.stepUpTokenTtl());
    }

    public Issued service(String serviceName) {
        return sign(builder("service:" + serviceName, JwtProperties.DEFAULT_AUDIENCE, auth.serviceTokenTtl())
                .claim("roles", List.of("SERVICE"))
                .claim("service", serviceName), auth.serviceTokenTtl());
    }

    public String keyId() {
        return keyId;
    }

    private JwtClaimsSet.Builder builder(String subject, String audience, java.time.Duration ttl) {
        Instant now = clock.instant();
        return JwtClaimsSet.builder()
                .issuer(jwt.issuer())
                .subject(subject)
                .audience(List.of(audience))
                .id(UUID.randomUUID().toString())
                .issuedAt(now)
                .notBefore(now)
                .expiresAt(now.plus(ttl));
    }

    private Issued sign(JwtClaimsSet.Builder claims, java.time.Duration ttl) {
        JwtClaimsSet set = claims.build();
        String token = encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.RS256).keyId(keyId).build(), set)).getTokenValue();
        return new Issued(token, set.getExpiresAt());
    }

    static RSAKey loadSigningKey(String location) {
        if (location == null || location.isBlank()) {
            throw new IllegalStateException("Refusing to start: tradeflow.security.auth.signing-key is not set. "
                    + "user-service needs the RSA private key that signs access tokens (the local Docker stack "
                    + "generates one; see `make keys`).");
        }
        try (InputStream in = new DefaultResourceLoader().getResource(location).getInputStream()) {
            String pem = new String(in.readAllBytes(), StandardCharsets.US_ASCII);
            if (!pem.contains("BEGIN PRIVATE KEY")) {
                throw new IllegalStateException("Signing key must be an unencrypted PKCS#8 PEM RSA private key");
            }
            byte[] der = Base64.getMimeDecoder().decode(pem.replaceAll("-----(BEGIN|END) PRIVATE KEY-----", "").trim());
            KeyFactory kf = KeyFactory.getInstance("RSA");
            RSAPrivateCrtKey priv = (RSAPrivateCrtKey) kf.generatePrivate(new PKCS8EncodedKeySpec(der));
            if (priv.getModulus().bitLength() < 2048) {
                throw new IllegalStateException("Signing key must be at least 2048 bits");
            }
            RSAPublicKey pub = (RSAPublicKey) kf.generatePublic(
                    new RSAPublicKeySpec(priv.getModulus(), priv.getPublicExponent()));
            return new RSAKey.Builder(pub).privateKey(priv).keyID(KeyIds.of(pub)).build();
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot load the token signing key from " + location + ": "
                    + e.getClass().getSimpleName(), e);
        }
    }
}
