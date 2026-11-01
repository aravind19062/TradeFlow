package com.luffy.tradeflow_live_trading_service.broker.fyers;

import com.luffy.tradeflow.common.web.ApiException;
import com.luffy.tradeflow_live_trading_service.broker.BrokerAdapter;
import com.luffy.tradeflow_live_trading_service.orders.LiveOrderStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Fyers API v3, READ-ONLY (phase 6). Linking uses Fyers' own login page (auth code + state);
 * TradeFlow never sees the Fyers password, PIN or TOTP. Funds, holdings, positions, the order book
 * and quotes are read with the user's access token.
 *
 * <p>{@link #placeOrder} and {@link #cancelOrder} always refuse without calling Fyers: real orders
 * are phase 7 and need the owner's explicit approval. This is deliberately not a configuration
 * switch. As a second lock, the Fyers app should be created without order permission.
 *
 * <p>Fyers has no PKCE; the {@code state} value (single use, hashed, bound to the user and expiring
 * after 10 minutes) protects the redirect. Fyers' refresh flow needs the user's PIN, which TradeFlow
 * must never handle, so no refresh token is kept: the session ends at Fyers' daily expiry (06:30 IST)
 * and the user links again.
 *
 * <p>Tokens and Fyers responses are never logged. The profile call is used only for the Fyers client
 * id; its other fields (name, e-mail, PAN, mobile) are ignored and never stored.
 */
public class FyersBrokerAdapter implements BrokerAdapter {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final LocalTime DAILY_EXPIRY = LocalTime.of(6, 30);
    private static final DateTimeFormatter ORDER_TIME = DateTimeFormatter.ofPattern("dd-MMM-yyyy HH:mm:ss", Locale.ENGLISH);
    private static final Logger log = LoggerFactory.getLogger(FyersBrokerAdapter.class);

    /** Fyers order-book status codes (API v3 docs, "Get Orders"). */
    private static final Map<Integer, String> STATUS = Map.of(
            1, LiveOrderStatus.CANCELLED,
            2, LiveOrderStatus.FILLED,
            4, LiveOrderStatus.OPEN,      // "transit": on its way to the exchange, still working
            5, LiveOrderStatus.REJECTED,
            6, LiveOrderStatus.OPEN,      // "pending"; PARTIALLY_FILLED when something has filled
            7, LiveOrderStatus.EXPIRED);
    private static final Map<Integer, String> TYPE = Map.of(1, "LIMIT", 2, "MARKET", 3, "STOP", 4, "STOP_LIMIT");

    private final FyersProperties properties;
    private final RestClient http;
    private final JsonMapper json;
    private final Clock clock;
    private final RateGuard userInfo;
    private final RateGuard marketData;

    public FyersBrokerAdapter(FyersProperties properties, JsonMapper json, Clock clock) {
        properties.requireComplete();
        this.properties = properties;
        this.json = json;
        this.clock = clock;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.connectTimeout());
        factory.setReadTimeout(properties.readTimeout());
        this.http = RestClient.builder().requestFactory(factory).build();
        // Published standard-plan limits: user info / transactions 10/s and 200/min, data 5/s and 50/min.
        this.userInfo = new RateGuard("account data", 8, 150, clock);
        this.marketData = new RateGuard("market data", 4, 40, clock);
    }

    @Override public String code() { return "fyers"; }

    @Override public boolean enabled() { return true; }

    @Override public boolean ordersEnabled() { return false; }

    // ------------------------------------------------------------------ authorisation

    @Override
    public String authorizationUrl(String state, String codeChallenge) {
        return UriComponentsBuilder.fromUriString(properties.apiUrl() + "/generate-authcode")
                .queryParam("client_id", properties.appId())
                .queryParam("redirect_uri", properties.redirectUri())
                .queryParam("response_type", "code")
                .queryParam("state", state)
                .encode().build().toUriString();
    }

    @Override
    public BrokerSession exchangeCode(String code, String codeVerifier) {
        JsonNode body = call(userInfo, () -> http.post().uri(properties.apiUrl() + "/validate-authcode")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("grant_type", "authorization_code", "appIdHash", appIdHash(), "code", code)));
        String accessToken = text(body, "access_token");
        if (accessToken == null) {
            throw brokerError(HttpStatus.BAD_GATEWAY, "Fyers did not return an access token");
        }
        JsonNode profile = get(userInfo, properties.apiUrl() + "/profile", accessToken);
        String clientId = text(profile.path("data"), "fy_id");
        return new BrokerSession(accessToken, null, nextExpiry(clock.instant()), clientId);
    }

    @Override
    public void revoke(String accessToken) {
        call(userInfo, () -> http.post().uri(properties.apiUrl() + "/logout").header("Authorization", auth(accessToken)));
    }

    // ------------------------------------------------------------------ account (read-only)

    @Override
    public Funds funds(String accessToken) {
        JsonNode body = get(userInfo, properties.apiUrl() + "/funds", accessToken);
        BigDecimal available = null;
        BigDecimal used = null;
        for (JsonNode row : body.path("fund_limit")) { // look rows up by title, not position (Fyers docs)
            String title = row.path("title").asString("");
            if ("Available Balance".equalsIgnoreCase(title)) {
                available = decimal(row, "equityAmount");
            } else if ("Utilized Amount".equalsIgnoreCase(title)) {
                used = decimal(row, "equityAmount");
            }
        }
        return new Funds(available, used, "INR", clock.instant());
    }

    @Override
    public List<Holding> holdings(String accessToken) {
        JsonNode body = get(userInfo, properties.apiUrl() + "/holdings", accessToken);
        List<Holding> out = new ArrayList<>();
        for (JsonNode h : body.path("holdings")) {
            String[] sym = split(text(h, "symbol"));
            out.add(new Holding(sym[0], sym[1], h.path("quantity").asLong(0), decimal(h, "costPrice"), decimal(h, "ltp")));
        }
        return out;
    }

    @Override
    public List<Position> positions(String accessToken) {
        JsonNode body = get(userInfo, properties.apiUrl() + "/positions", accessToken);
        List<Position> out = new ArrayList<>();
        for (JsonNode p : body.path("netPositions")) {
            String[] sym = split(text(p, "symbol"));
            out.add(new Position(sym[0], sym[1], p.path("netQty").asLong(0), decimal(p, "netAvg"), decimal(p, "pl")));
        }
        return out;
    }

    @Override
    public List<BrokerOrder> orders(String accessToken) {
        JsonNode body = get(userInfo, properties.apiUrl() + "/orders", accessToken);
        List<BrokerOrder> out = new ArrayList<>();
        for (JsonNode o : body.path("orderBook")) {
            String[] sym = split(text(o, "symbol"));
            long qty = o.path("qty").asLong(0);
            long filled = o.path("filledQty").asLong(0);
            int code = o.path("status").asInt(0);
            String status = STATUS.get(code);
            if (LiveOrderStatus.OPEN.equals(status) && filled > 0 && filled < qty) {
                status = LiveOrderStatus.PARTIALLY_FILLED;
            }
            BigDecimal traded = decimal(o, "tradedPrice");
            out.add(new BrokerOrder(text(o, "id"), clientTag(text(o, "orderTag")), sym[0], sym[1],
                    o.path("side").asInt(0) == -1 ? "SELL" : "BUY", TYPE.getOrDefault(o.path("type").asInt(0), "UNKNOWN"),
                    qty, filled, decimal(o, "limitPrice"), traded != null && traded.signum() > 0 ? traded : null,
                    status == null ? "FYERS_STATUS_" + code : status, emptyToNull(text(o, "message")),
                    orderTime(text(o, "orderDateTime"))));
        }
        return out;
    }

    @Override
    public List<Quote> quotes(String accessToken, List<String> instruments) {
        if (instruments.isEmpty()) {
            return List.of();
        }
        if (instruments.size() > 50) {
            throw ApiException.badRequest("TOO_MANY_SYMBOLS", "At most 50 instruments per quote request");
        }
        String symbols = String.join(",", instruments.stream().map(FyersBrokerAdapter::toFyersSymbol).toList());
        String url = UriComponentsBuilder.fromUriString(properties.dataUrl() + "/quotes")
                .queryParam("symbols", symbols).encode().build().toUriString();
        JsonNode body = get(marketData, url, accessToken);
        List<Quote> out = new ArrayList<>();
        for (JsonNode d : body.path("d")) {
            if (!"ok".equals(d.path("s").asString(""))) {
                continue; // per-symbol error (unknown symbol): leave it out
            }
            JsonNode v = d.path("v");
            String[] sym = split(text(d, "n"));
            long tt = v.path("tt").asLong(0); // epoch seconds (sent as a string or a number)
            out.add(new Quote(sym[0], sym[1], decimal(v, "lp"), decimal(v, "bid"), decimal(v, "ask"),
                    tt > 0 ? Instant.ofEpochSecond(tt) : clock.instant(), "FYERS"));
        }
        return out;
    }

    // ------------------------------------------------------------------ orders: phase 7, not approved

    @Override
    public BrokerOrderAck placeOrder(String accessToken, OrderRequest request) {
        throw ordersNotEnabled();
    }

    @Override
    public BrokerOrderAck cancelOrder(String accessToken, String brokerOrderId) {
        throw ordersNotEnabled();
    }

    static ApiException ordersNotEnabled() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "LIVE_ORDERS_NOT_ENABLED",
                "Your Fyers account is linked read-only. Placing and cancelling real orders from TradeFlow is not "
                        + "enabled yet; use Fyers directly to manage orders.");
    }

    // ------------------------------------------------------------------ helpers

    /** The first 06:30 IST after {@code now}: Fyers access tokens expire daily at that time. */
    static Instant nextExpiry(Instant now) {
        ZonedDateTime ist = now.atZone(IST);
        ZonedDateTime expiry = ist.toLocalDate().atTime(DAILY_EXPIRY).atZone(IST);
        return (expiry.isAfter(ist) ? expiry : expiry.plusDays(1)).toInstant();
    }

    /** SHA-256 of {@code app_id:app_secret}, hex (Fyers "validate-authcode"). */
    String appIdHash() {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((properties.appId() + ":" + properties.secretKey()).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String auth(String accessToken) {
        return properties.appId() + ":" + accessToken;
    }

    private JsonNode get(RateGuard guard, String url, String accessToken) {
        return call(guard, () -> http.get().uri(url).header("Authorization", auth(accessToken)));
    }

    /**
     * Sends the request and returns the JSON body when Fyers says {@code "s": "ok"}. Everything else
     * becomes an {@link ApiException} with a stable code; Fyers' message is passed on, tokens never.
     */
    private JsonNode call(RateGuard guard, java.util.function.Supplier<RestClient.RequestHeadersSpec<?>> request) {
        guard.acquire();
        Raw raw;
        try {
            raw = request.get().exchange((req, res) -> new Raw(res.getStatusCode().value(),
                    new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8)));
        } catch (RestClientException e) {
            log.warn("Fyers request failed: {}", e.getClass().getSimpleName());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "BROKER_UNAVAILABLE",
                    "Fyers could not be reached. Try again shortly.");
        }
        JsonNode body;
        try {
            body = json.readTree(raw.body());
        } catch (JacksonException e) {
            log.warn("Fyers returned a non-JSON response (HTTP {})", raw.status());
            throw brokerError(HttpStatus.BAD_GATEWAY, "Fyers returned an unexpected response");
        }
        if ("ok".equals(body.path("s").asString("")) && raw.status() < 400) {
            return body;
        }
        int code = body.path("code").asInt(0);
        String message = text(body, "message");
        log.info("Fyers refused a request: HTTP {} code {}", raw.status(), code);
        if (code == -8 || code == -15 || code == -16 || code == -17 || raw.status() == 401) {
            throw new ApiException(HttpStatus.CONFLICT, "BROKER_SESSION_EXPIRED",
                    "Your Fyers session has ended (Fyers sessions end daily at 06:30 IST). Disconnect and link again.");
        }
        if (code == -429 || raw.status() == 429) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "BROKER_RATE_LIMITED",
                    "Fyers is rate-limiting requests. Wait a minute and try again.");
        }
        throw brokerError(HttpStatus.BAD_GATEWAY, "Fyers refused the request (code " + code + ")"
                + (message == null || message.isBlank() ? "" : ": " + truncate(message)));
    }

    private record Raw(int status, String body) {
    }

    private static ApiException brokerError(HttpStatus status, String message) {
        return new ApiException(status, "BROKER_ERROR", message);
    }

    /** "NSE:SBIN-EQ" → ["NSE", "SBIN"]; other series (e.g. "-BE", BSE "-A") keep their suffix. */
    static String[] split(String fyersSymbol) {
        if (fyersSymbol == null) {
            return new String[]{null, null};
        }
        int colon = fyersSymbol.indexOf(':');
        String exchange = colon < 0 ? null : fyersSymbol.substring(0, colon);
        String symbol = colon < 0 ? fyersSymbol : fyersSymbol.substring(colon + 1);
        if ("NSE".equals(exchange) && symbol.endsWith("-EQ")) {
            symbol = symbol.substring(0, symbol.length() - 3);
        }
        return new String[]{exchange, symbol};
    }

    /** "NSE:TCS" → "NSE:TCS-EQ" (NSE cash equity); anything with a series is passed through. */
    static String toFyersSymbol(String instrument) {
        int colon = instrument.indexOf(':');
        String exchange = colon < 0 ? "NSE" : instrument.substring(0, colon).toUpperCase(Locale.ROOT);
        String symbol = (colon < 0 ? instrument : instrument.substring(colon + 1)).toUpperCase(Locale.ROOT);
        return exchange + ":" + ("NSE".equals(exchange) && !symbol.contains("-") ? symbol + "-EQ" : symbol);
    }

    /** User order tags come back as "1:&lt;tag&gt;"; "2:" tags are Fyers' own and are not TradeFlow's. */
    static String clientTag(String orderTag) {
        return orderTag != null && orderTag.startsWith("1:") && orderTag.length() > 2 ? orderTag.substring(2) : null;
    }

    static Instant orderTime(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(s, ORDER_TIME).atZone(IST).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asString();
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull() || v.asString("").isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(v.asString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static String truncate(String s) {
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }
}
