package com.luffy.tradeflow_live_trading_service.broker.fyers;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Fyers API v3 app credentials and endpoints. The app is created by the account owner at
 * myapi.fyers.in; {@code secretKey} comes from the environment (never from a committed file) and is
 * only used to compute the {@code appIdHash} for the auth-code exchange.
 *
 * @param redirectUri must equal the redirect URL registered for the app (TradeFlow's
 *                    {@code /live/broker/callback} page)
 */
@ConfigurationProperties("tradeflow.live.fyers")
public record FyersProperties(String appId, String secretKey, String redirectUri, String apiUrl, String dataUrl,
                              Duration connectTimeout, Duration readTimeout) {

    public FyersProperties {
        apiUrl = blank(apiUrl) ? "https://api-t1.fyers.in/api/v3" : strip(apiUrl);
        dataUrl = blank(dataUrl) ? "https://api-t1.fyers.in/data" : strip(dataUrl);
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(3) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(10) : readTimeout;
    }

    /** Refuses to start with missing credentials rather than failing on the first user's click. */
    public void requireComplete() {
        if (blank(appId) || blank(secretKey) || blank(redirectUri)) {
            throw new IllegalStateException("Refusing to start: tradeflow.live.broker=fyers needs "
                    + "TRADEFLOW_FYERS_APP_ID, TRADEFLOW_FYERS_SECRET_KEY and TRADEFLOW_FYERS_REDIRECT_URI");
        }
        if (!redirectUri.startsWith("https://") && !redirectUri.startsWith("http://localhost")
                && !redirectUri.startsWith("http://127.0.0.1")) {
            throw new IllegalStateException("Refusing to start: the Fyers redirect URI must be https (or localhost)");
        }
    }

    @Override
    public String toString() {
        return "FyersProperties[appId=" + appId + ", redirectUri=" + redirectUri + ", apiUrl=" + apiUrl
                + ", dataUrl=" + dataUrl + ", secretKey=<redacted>]";
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String strip(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
