package com.luffy.tradeflow.common.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * The public keys every service uses to verify access tokens. Each key is a PEM file named
 * {@code <kid>.pem}, where the key id is derived from the key ({@link KeyIds}); several files
 * allow key rotation. Only
 * user-service holds a private key, so no other service can mint a token.
 */
public final class PublicKeyRing {

    private PublicKeyRing() {
    }

    /** @param pattern e.g. {@code file:/run/tradeflow/jwt/public/*.pem} or {@code classpath*:jwt-test/public/*.pem} */
    public static JWKSet load(String pattern) {
        if (pattern == null || pattern.isBlank()) {
            throw new IllegalStateException("Refusing to start: tradeflow.security.jwt.public-keys is not set. "
                    + "Point it at the RSA public key(s) of user-service, e.g. file:/run/tradeflow/jwt/public/*.pem "
                    + "(the local Docker stack generates them; see `make keys`).");
        }
        List<com.nimbusds.jose.jwk.JWK> keys = new ArrayList<>();
        try {
            for (Resource resource : new PathMatchingResourcePatternResolver().getResources(pattern)) {
                String name = resource.getFilename();
                if (name == null || !name.endsWith(".pem") || !resource.isReadable()) {
                    continue;
                }
                try (InputStream in = resource.getInputStream()) {
                    RSAPublicKey key = parsePublicKey(new String(in.readAllBytes(), StandardCharsets.US_ASCII));
                    String kid = KeyIds.of(key);
                    if (!name.equals(kid + ".pem")) {
                        throw new IllegalStateException("Public key file " + name + " must be named " + kid
                                + ".pem (its key id)");
                    }
                    keys.add(new RSAKey.Builder(key).keyID(kid)
                            .keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256).build());
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read JWT public keys from " + pattern + ": " + e.getMessage(), e);
        }
        if (keys.isEmpty()) {
            throw new IllegalStateException("Refusing to start: no RSA public keys found at " + pattern);
        }
        return new JWKSet(keys);
    }

    public static RSAPublicKey parsePublicKey(String pem) {
        try {
            byte[] der = Base64.getMimeDecoder().decode(pem.replaceAll("-----(BEGIN|END) PUBLIC KEY-----", "").trim());
            return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalStateException("Not an RSA public key in X.509 PEM format: " + e.getMessage(), e);
        }
    }
}
