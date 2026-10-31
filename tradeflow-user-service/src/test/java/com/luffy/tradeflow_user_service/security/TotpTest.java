package com.luffy.tradeflow_user_service.security;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class TotpTest {

    /** RFC 6238 Appendix B, SHA-1 test vectors (8 digits, 30 s steps). */
    @Test
    void matchesRfc6238TestVectors() {
        byte[] key = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
        long[][] vectors = {{59, 94287082}, {1111111109, 7081804}, {1111111111, 14050471},
                {1234567890, 89005924}, {2000000000, 69279037}, {20000000000L, 65353130}};
        for (long[] v : vectors) {
            assertThat(Totp.code(key, Totp.step(Instant.ofEpochSecond(v[0])), "HmacSHA1", 8))
                    .isEqualTo(String.format("%08d", v[1]));
        }
    }

    @Test
    void base32RoundTripsAndSecretsHave160Bits() {
        String secret = Totp.newSecret(new SecureRandom());
        assertThat(secret).matches("[A-Z2-7]{32}");
        assertThat(Totp.decodeBase32(secret)).hasSize(20);
        assertThat(Totp.base32(Totp.decodeBase32(secret))).isEqualTo(secret);
    }

    @Test
    void acceptsOneStepOfDriftAndReportsTheStep() {
        String secret = Totp.newSecret(new SecureRandom());
        Instant now = Instant.parse("2026-10-31T10:00:15Z");
        long step = Totp.step(now);
        assertThat(Totp.verify(secret, Totp.code(secret, step), now)).hasValue(step);
        assertThat(Totp.verify(secret, Totp.code(secret, step - 1), now)).hasValue(step - 1);
        assertThat(Totp.verify(secret, Totp.code(secret, step + 1), now)).hasValue(step + 1);
        assertThat(Totp.verify(secret, Totp.code(secret, step - 2), now)).isEmpty();
        assertThat(Totp.verify(secret, "12345", now)).isEmpty();
        assertThat(Totp.verify(secret, "abcdef", now)).isEmpty();
    }

    @Test
    void otpauthUriIsWhatAuthenticatorAppsExpect() {
        assertThat(Totp.otpauthUri("TradeFlow", "a b@x.dev", "ABC"))
                .isEqualTo("otpauth://totp/TradeFlow:a%20b%40x.dev?secret=ABC&issuer=TradeFlow&algorithm=SHA1&digits=6&period=30");
    }
}
