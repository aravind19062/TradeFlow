package com.luffy.tradeflow.common.security;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.util.HexFormat;

/**
 * Key ids are derived from the key itself: the first 16 hex characters of SHA-256 over the
 * DER-encoded public key. user-service (signer), every verifier and the key generator
 * ({@code docker/keygen.sh}: {@code openssl pkey -pubout -outform DER | openssl dgst -sha256})
 * compute the same id independently, so no key id has to be configured or kept in sync.
 */
public final class KeyIds {

    private KeyIds() {
    }

    public static String of(PublicKey key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getEncoded());
            return HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
