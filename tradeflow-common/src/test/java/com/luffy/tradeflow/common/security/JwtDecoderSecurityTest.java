package com.luffy.tradeflow.common.security;

import com.luffy.tradeflow.common.testsupport.TestJwts;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The shared decoder accepts only RS256 tokens signed by user-service's key, for this audience. */
class JwtDecoderSecurityTest {

    private final JwtDecoder decoder = JwtSecurityConfig.decoder(
            PublicKeyRing.load(TestJwts.PUBLIC_KEYS), TestJwts.ISSUER, JwtProperties.DEFAULT_AUDIENCE);

    @Test
    void acceptsAValidUserToken() {
        assertThat(decoder.decode(TestJwts.user(7)).getSubject()).isEqualTo("7");
    }

    @Test
    void rejectsATokenSignedWithAnyOtherKey() {
        assertThatThrownBy(() -> decoder.decode(TestJwts.untrustedUser(7))).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsExpiredTokensAndOtherAudiences() {
        assertThatThrownBy(() -> decoder.decode(TestJwts.expiredUser(7))).isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> decoder.decode(TestJwts.withAudience(7, JwtProperties.LIVE_AUDIENCE, Map.of())))
                .isInstanceOf(JwtException.class).hasMessageContaining("aud");
    }

    @Test
    void rejectsSymmetricAndUnsignedTokens() throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer(TestJwts.ISSUER).subject("1")
                .audience("tradeflow").claim("roles", List.of("ADMIN"))
                .expirationTime(new Date(System.currentTimeMillis() + 60_000)).build();
        // The old HS256 scheme, even with the former shared secret.
        SignedJWT hs = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).keyID(TestJwts.KEY_ID).build(), claims);
        hs.sign(new MACSigner("local-dev-only-insecure-jwt-secret-do-not-use-in-prod".getBytes()));
        assertThatThrownBy(() -> decoder.decode(hs.serialize())).isInstanceOf(JwtException.class);
        // Algorithm confusion: HMAC keyed with the RSA public key bytes.
        byte[] publicKeyBytes = PublicKeyRing.load(TestJwts.PUBLIC_KEYS).getKeys().get(0).toRSAKey()
                .toPublicKey().getEncoded();
        SignedJWT confused = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).keyID(TestJwts.KEY_ID).build(),
                claims);
        confused.sign(new MACSigner(java.util.Arrays.copyOf(publicKeyBytes, Math.max(32, publicKeyBytes.length))));
        assertThatThrownBy(() -> decoder.decode(confused.serialize())).isInstanceOf(JwtException.class);
        // alg=none
        assertThatThrownBy(() -> decoder.decode(new PlainJWT(claims).serialize())).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsATamperedPayload() {
        String[] parts = TestJwts.user(7).split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1])).replace("\"USER\"", "\"ADMIN\"");
        String forged = parts[0] + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes())
                + "." + parts[2];
        assertThatThrownBy(() -> decoder.decode(forged)).isInstanceOf(JwtException.class);
    }

    @Test
    void refusesToStartWithoutPublicKeys() {
        assertThatThrownBy(() -> PublicKeyRing.load("")).hasMessageContaining("public-keys is not set");
        assertThatThrownBy(() -> PublicKeyRing.load("classpath*:no-such-dir/*.pem"))
                .hasMessageContaining("no RSA public keys found");
    }
}
