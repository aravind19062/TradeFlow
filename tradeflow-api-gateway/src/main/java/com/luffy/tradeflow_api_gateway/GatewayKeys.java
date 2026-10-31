package com.luffy.tradeflow_api_gateway;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

/**
 * Loads user-service's RSA public keys (files named {@code <kid>.pem}, kid = first 16 hex chars
 * of SHA-256 over the DER key). Same rules as tradeflow-common's PublicKeyRing; duplicated so the
 * gateway does not pull in the services' JPA/Kafka stack.
 */
final class GatewayKeys {

    private GatewayKeys() {
    }

    static JWKSet load(String pattern) {
        if (pattern == null || pattern.isBlank()) {
            throw new IllegalStateException("Refusing to start: tradeflow.security.jwt.public-keys is not set");
        }
        List<JWK> keys = new ArrayList<>();
        try {
            for (Resource r : new PathMatchingResourcePatternResolver().getResources(pattern)) {
                String name = r.getFilename();
                if (name == null || !name.endsWith(".pem") || !r.isReadable()) {
                    continue;
                }
                try (InputStream in = r.getInputStream()) {
                    String pem = new String(in.readAllBytes(), StandardCharsets.US_ASCII);
                    byte[] der = Base64.getMimeDecoder().decode(pem.replaceAll("-----(BEGIN|END) PUBLIC KEY-----", "").trim());
                    RSAPublicKey key = (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
                    String kid = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(der)).substring(0, 16);
                    if (!name.equals(kid + ".pem")) {
                        throw new IllegalStateException("Public key file " + name + " must be named " + kid + ".pem");
                    }
                    keys.add(new RSAKey.Builder(key).keyID(kid).keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256).build());
                }
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read JWT public keys from " + pattern + ": " + e.getMessage(), e);
        }
        if (keys.isEmpty()) {
            throw new IllegalStateException("Refusing to start: no RSA public keys found at " + pattern);
        }
        return new JWKSet(keys);
    }
}
