package com.luffy.tradeflow.common.crypto;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DataEncryptorTest {

    private final DataEncryptor encryptor = DataEncryptor.fromKeyRing("classpath:crypto-test/data-keys.properties");

    @Test
    void roundTripsAndNeverRepeatsCiphertext() {
        String a = encryptor.encrypt("JBSWY3DPEHPK3PXP", "totp:user:1");
        String b = encryptor.encrypt("JBSWY3DPEHPK3PXP", "totp:user:1");
        assertThat(a).isNotEqualTo(b).startsWith("v1:test-k2:").doesNotContain("JBSWY3DPEHPK3PXP");
        assertThat(encryptor.decrypt(a, "totp:user:1")).isEqualTo("JBSWY3DPEHPK3PXP");
    }

    @Test
    void ciphertextIsBoundToItsRecord() {
        String ct = encryptor.encrypt("secret", "totp:user:1");
        assertThatThrownBy(() -> encryptor.decrypt(ct, "totp:user:2")).hasMessageContaining("Decryption failed");
    }

    @Test
    void detectsTampering() {
        String ct = encryptor.encrypt("secret", "ctx");
        String[] parts = ct.split(":", 3);
        byte[] raw = java.util.Base64.getDecoder().decode(parts[2]);
        raw[raw.length - 1] ^= 1;
        String tampered = parts[0] + ":" + parts[1] + ":" + java.util.Base64.getEncoder().encodeToString(raw);
        assertThatThrownBy(() -> encryptor.decrypt(tampered, "ctx")).hasMessageContaining("Decryption failed");
    }

    @Test
    void oldKeysStayReadableAfterRotation() {
        byte[] k1 = new byte[32];
        byte[] k2 = new byte[32];
        k2[0] = 1;
        DataEncryptor before = new DataEncryptor(Map.of("k1", k1), "k1");
        String old = before.encrypt("broker-token", "broker:9");
        DataEncryptor after = new DataEncryptor(Map.of("k1", k1, "k2", k2), "k2");
        assertThat(after.decrypt(old, "broker:9")).isEqualTo("broker-token");
        assertThat(after.needsRotation(old)).isTrue();
        assertThat(after.needsRotation(after.encrypt("x", "c"))).isFalse();
    }

    @Test
    void refusesBadKeyRings() {
        assertThatThrownBy(() -> new DataEncryptor(Map.of("k1", new byte[16]), "k1")).hasMessageContaining("32 bytes");
        assertThatThrownBy(() -> new DataEncryptor(Map.of("k1", new byte[32]), "k9")).hasMessageContaining("not in the key ring");
        assertThatThrownBy(() -> DataEncryptor.fromKeyRing("")).hasMessageContaining("keyring is not set");
        // Key ids only, never key material.
        java.util.Properties ring = new java.util.Properties();
        try (var in = getClass().getClassLoader().getResourceAsStream("crypto-test/data-keys.properties")) {
            ring.load(in);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        assertThat(encryptor.toString()).contains("test-k2")
                .doesNotContain(ring.getProperty("test-k1")).doesNotContain(ring.getProperty("test-k2"));
    }
}
