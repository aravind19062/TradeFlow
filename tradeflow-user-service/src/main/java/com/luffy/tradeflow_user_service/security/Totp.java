package com.luffy.tradeflow_user_service.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Locale;
import java.util.OptionalLong;

/**
 * RFC 6238 TOTP with the parameters every authenticator app supports: HMAC-SHA1, 30-second
 * steps, 6 digits. Verification allows one step of clock drift either way and returns the
 * matched step so callers can refuse its reuse (replay protection).
 */
public final class Totp {

    public static final int DIGITS = 6;
    public static final long STEP_SECONDS = 30;
    public static final int SECRET_BYTES = 20;
    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private Totp() {
    }

    public static String newSecret(SecureRandom random) {
        byte[] bytes = new byte[SECRET_BYTES];
        random.nextBytes(bytes);
        return base32(bytes);
    }

    public static long step(Instant at) {
        return Math.floorDiv(at.getEpochSecond(), STEP_SECONDS);
    }

    public static String code(byte[] key, long step, String algorithm, int digits) {
        try {
            Mac mac = Mac.getInstance(algorithm);
            mac.init(new SecretKeySpec(key, "RAW"));
            byte[] h = mac.doFinal(java.nio.ByteBuffer.allocate(8).putLong(step).array());
            int offset = h[h.length - 1] & 0x0f;
            int binary = ((h[offset] & 0x7f) << 24) | ((h[offset + 1] & 0xff) << 16)
                    | ((h[offset + 2] & 0xff) << 8) | (h[offset + 3] & 0xff);
            int otp = binary % (int) Math.pow(10, digits);
            return String.format("%0" + digits + "d", otp);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String code(String base32Secret, long step) {
        return code(decodeBase32(base32Secret), step, "HmacSHA1", DIGITS);
    }

    /** The matching step within ±1 of now, if the code is valid. Constant-time comparison. */
    public static OptionalLong verify(String base32Secret, String candidate, Instant now) {
        if (candidate == null || !candidate.matches("\\d{" + DIGITS + "}")) {
            return OptionalLong.empty();
        }
        byte[] key = decodeBase32(base32Secret);
        long current = step(now);
        OptionalLong match = OptionalLong.empty();
        for (long s = current - 1; s <= current + 1; s++) {
            if (java.security.MessageDigest.isEqual(code(key, s, "HmacSHA1", DIGITS).getBytes(), candidate.getBytes())) {
                match = OptionalLong.of(s);
            }
        }
        return match;
    }

    public static String otpauthUri(String issuer, String account, String base32Secret) {
        String label = enc(issuer) + ":" + enc(account);
        return "otpauth://totp/" + label + "?secret=" + base32Secret + "&issuer=" + enc(issuer)
                + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + STEP_SECONDS;
    }

    private static String enc(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
    }

    static String base32(byte[] data) {
        StringBuilder out = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                out.append(BASE32.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) {
            out.append(BASE32.charAt((buffer << (5 - bits)) & 31));
        }
        return out.toString();
    }

    static byte[] decodeBase32(String s) {
        String clean = s.replace("=", "").replace(" ", "").toUpperCase(Locale.ROOT);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int buffer = 0;
        int bits = 0;
        for (char c : clean.toCharArray()) {
            int v = BASE32.indexOf(c);
            if (v < 0) {
                throw new IllegalArgumentException("Invalid base32 character");
            }
            buffer = (buffer << 5) | v;
            bits += 5;
            if (bits >= 8) {
                out.write((buffer >> (bits - 8)) & 0xff);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }
}
