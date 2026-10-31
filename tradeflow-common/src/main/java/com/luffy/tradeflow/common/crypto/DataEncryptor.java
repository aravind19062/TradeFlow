package com.luffy.tradeflow.common.crypto;

import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * AES-256-GCM encryption for secrets at rest (TOTP seeds, broker access tokens).
 *
 * <p>Ciphertext format: {@code v1:<keyId>:<base64(12-byte nonce || ciphertext || tag)>}. The key id
 * makes rotation possible (old values stay readable while new ones use the active key). Every
 * value is bound to a caller-supplied <em>context</em> (e.g. {@code totp:user:42}) as GCM
 * additional data, so a ciphertext copied onto another record fails to decrypt.
 *
 * <p>Keys come from a properties file ({@code active=<id>} plus {@code <id>=<base64 32 bytes>}),
 * provisioned outside the application (Docker volume / secret manager); they are never in Git.
 * Plaintext and keys are never logged.
 */
public final class DataEncryptor {

    private static final String PREFIX = "v1";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final Map<String, byte[]> keys;
    private final String activeKeyId;
    private final SecureRandom random = new SecureRandom();

    public DataEncryptor(Map<String, byte[]> keys, String activeKeyId) {
        if (activeKeyId == null || !keys.containsKey(activeKeyId)) {
            throw new IllegalStateException("Refusing to start: the active data-encryption key '" + activeKeyId
                    + "' is not in the key ring");
        }
        keys.forEach((id, key) -> {
            if (key.length != 32) {
                throw new IllegalStateException("Data-encryption key '" + id + "' must be 32 bytes (AES-256)");
            }
            if (id.contains(":")) {
                throw new IllegalStateException("Data-encryption key id must not contain ':'");
            }
        });
        this.keys = Collections.unmodifiableMap(new LinkedHashMap<>(keys));
        this.activeKeyId = activeKeyId;
    }

    /** Loads {@code active=<id>} and {@code <id>=<base64>} entries from a properties resource. */
    public static DataEncryptor fromKeyRing(String location) {
        if (location == null || location.isBlank()) {
            throw new IllegalStateException("Refusing to start: tradeflow.crypto.keyring is not set (data-encryption "
                    + "keys for secrets at rest; the local Docker stack generates them, see `make keys`)");
        }
        Resource resource = new DefaultResourceLoader().getResource(location);
        Properties props = new Properties();
        try (InputStream in = resource.getInputStream()) {
            props.load(in);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read the data-encryption key ring at " + location, e);
        }
        Map<String, byte[]> keys = new LinkedHashMap<>();
        for (String name : props.stringPropertyNames()) {
            if (!name.equals("active")) {
                keys.put(name, Base64.getDecoder().decode(props.getProperty(name).trim()));
            }
        }
        return new DataEncryptor(keys, props.getProperty("active", "").trim());
    }

    public String activeKeyId() {
        return activeKeyId;
    }

    public String encrypt(String plaintext, String context) {
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, keys.get(activeKeyId), nonce, context, activeKeyId);
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            ByteBuffer out = ByteBuffer.allocate(nonce.length + ct.length).put(nonce).put(ct);
            return PREFIX + ":" + activeKeyId + ":" + Base64.getEncoder().encodeToString(out.array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    public String decrypt(String value, String context) {
        String[] parts = value == null ? new String[0] : value.split(":", 3);
        if (parts.length != 3 || !PREFIX.equals(parts[0])) {
            throw new IllegalStateException("Unrecognised ciphertext format");
        }
        byte[] key = keys.get(parts[1]);
        if (key == null) {
            throw new IllegalStateException("Ciphertext uses unknown key id '" + parts[1] + "'");
        }
        byte[] all = Base64.getDecoder().decode(parts[2]);
        if (all.length <= NONCE_BYTES) {
            throw new IllegalStateException("Ciphertext too short");
        }
        byte[] nonce = java.util.Arrays.copyOfRange(all, 0, NONCE_BYTES);
        try {
            Cipher cipher = cipher(Cipher.DECRYPT_MODE, key, nonce, context, parts[1]);
            return new String(cipher.doFinal(all, NONCE_BYTES, all.length - NONCE_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            // Wrong key, wrong context or tampered data: never reveal which.
            throw new IllegalStateException("Decryption failed (wrong key or context, or tampered data)");
        }
    }

    /** True when the value was not encrypted with the active key (candidate for re-encryption). */
    public boolean needsRotation(String value) {
        return value != null && !value.startsWith(PREFIX + ":" + activeKeyId + ":");
    }

    private static Cipher cipher(int mode, byte[] key, byte[] nonce, String context, String keyId)
            throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
        cipher.updateAAD((PREFIX + "|" + keyId + "|" + context).getBytes(StandardCharsets.UTF_8));
        return cipher;
    }

    @Override
    public String toString() {
        return "DataEncryptor[active=" + activeKeyId + ", keys=" + keys.keySet() + "]";
    }
}
