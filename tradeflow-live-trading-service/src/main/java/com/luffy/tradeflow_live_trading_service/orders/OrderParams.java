package com.luffy.tradeflow_live_trading_service.orders;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** The exact parameters a confirmation is bound to; any change needs a new preview. */
public record OrderParams(String exchange, String symbol, String side, String type, long quantity,
                          BigDecimal limitPrice, BigDecimal triggerPrice) {

    public String sha256(long userId) {
        String canonical = String.join("|", String.valueOf(userId), exchange, symbol, side, type, String.valueOf(quantity),
                limitPrice == null ? "-" : limitPrice.stripTrailingZeros().toPlainString(),
                triggerPrice == null ? "-" : triggerPrice.stripTrailingZeros().toPlainString());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
