package com.luffy.tradeflow_live_trading_service.broker.angelone;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.regex.Pattern;

/**
 * Angel One SmartAPI app settings. The app is created by the account owner at smartapi.angelone.in;
 * its API key comes from the environment (never from a committed file). TradeFlow needs nothing
 * else from the user: no client password, PIN or TOTP secret, because the user signs in on Angel
 * One's own publisher-login page.
 *
 * <p>SmartAPI requests carry the caller's local IP, public IP and MAC address as headers
 * ({@code X-ClientLocalIP}, {@code X-ClientPublicIP}, {@code X-MACAddress}), as the official SDKs do.
 * They are configurable so the real values of the machine TradeFlow runs on can be given.
 *
 * @param apiKey      the SmartAPI app's API key (sent as {@code X-PrivateKey}; not a secret password,
 *                    but kept out of git like every credential)
 * @param loginUrl    Angel One's publisher-login page
 * @param apiUrl      the SmartAPI REST host
 */
@ConfigurationProperties("tradeflow.live.angelone")
public record AngelOneProperties(String apiKey, String loginUrl, String apiUrl, String clientLocalIp,
                                 String clientPublicIp, String macAddress, Duration connectTimeout,
                                 Duration readTimeout) {

    private static final Pattern IPV4 = Pattern.compile("^(\\d{1,3}\\.){3}\\d{1,3}$");
    private static final Pattern MAC = Pattern.compile("^([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}$");

    public AngelOneProperties {
        loginUrl = blank(loginUrl) ? "https://smartapi.angelone.in/publisher-login" : strip(loginUrl);
        apiUrl = blank(apiUrl) ? "https://apiconnect.angelone.in" : strip(apiUrl);
        clientLocalIp = blank(clientLocalIp) ? "127.0.0.1" : clientLocalIp.trim();
        clientPublicIp = blank(clientPublicIp) ? clientLocalIp : clientPublicIp.trim();
        macAddress = blank(macAddress) ? "00:00:00:00:00:00" : macAddress.trim();
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(3) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(8) : readTimeout;
    }

    /** Refuses to start with missing or malformed settings rather than failing on the first user's click. */
    public void requireComplete() {
        if (blank(apiKey)) {
            throw new IllegalStateException("Refusing to start: tradeflow.live.broker=angelone needs TRADEFLOW_ANGELONE_API_KEY");
        }
        if (!IPV4.matcher(clientLocalIp).matches() || !IPV4.matcher(clientPublicIp).matches()) {
            throw new IllegalStateException("Refusing to start: TRADEFLOW_ANGELONE_CLIENT_LOCAL_IP / _CLIENT_PUBLIC_IP must be IPv4 addresses");
        }
        if (!MAC.matcher(macAddress).matches()) {
            throw new IllegalStateException("Refusing to start: TRADEFLOW_ANGELONE_MAC_ADDRESS must look like 00:11:22:33:44:55");
        }
    }

    @Override
    public String toString() {
        return "AngelOneProperties[apiKey=<redacted>, loginUrl=" + loginUrl + ", apiUrl=" + apiUrl + "]";
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String strip(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
