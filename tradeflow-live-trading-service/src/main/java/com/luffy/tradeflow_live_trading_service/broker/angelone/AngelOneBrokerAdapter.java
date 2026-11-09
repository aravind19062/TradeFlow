package com.luffy.tradeflow_live_trading_service.broker.angelone;

import com.luffy.tradeflow.common.web.ApiException;
import com.luffy.tradeflow_live_trading_service.broker.BrokerAdapter;
import com.luffy.tradeflow_live_trading_service.broker.RateGuard;
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
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Angel One SmartAPI, READ-ONLY (phase 6).
 *
 * <p><b>Linking.</b> The user signs in on Angel One's own publisher-login page
 * ({@code publisher-login?api_key=...}, as in Angel One's official SDKs), so TradeFlow never sees
 * the client password, PIN or TOTP. Angel One redirects back to the app's registered redirect URL
 * with an {@code auth_token}; TradeFlow accepts it only together with the single-use {@code state}
 * it issued, and only after a {@code getProfile} call with that token succeeds. The {@code
 * loginByPassword} API (client code + PIN + TOTP) is deliberately not used.
 *
 * <p><b>Sessions.</b> No refresh token is kept: the session lasts as long as the token itself
 * (its JWT {@code exp}, capped at 24 hours) and the user links again afterwards.
 *
 * <p><b>Reads.</b> {@code getRMS} (funds), {@code getHolding}, {@code getPosition} (intraday and
 * carry-forward, with the product type) and {@code getOrderBook}. Quotes are not offered: SmartAPI
 * quotes are keyed by Angel One's numeric symbol tokens, which would need its instrument master.
 *
 * <p><b>Orders.</b> {@link #placeOrder} and {@link #cancelOrder} always refuse without calling
 * Angel One: real orders are phase 7 and need the owner's explicit approval. This is deliberately
 * not a configuration switch.
 *
 * <p>Tokens and Angel One responses are never logged. Of the profile only {@code clientcode} is
 * used; name, e-mail and mobile are ignored and never stored.
 */
public class AngelOneBrokerAdapter implements BrokerAdapter {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    /** Upper bound on how long TradeFlow treats a token as usable, whatever its own expiry says. */
    static final Duration MAX_SESSION = Duration.ofHours(24);
    /** Used only when the token carries no readable expiry: short, so a dead token is not kept around. */
    static final Duration FALLBACK_SESSION = Duration.ofHours(6);

    public static final String PROFILE = "/rest/secure/angelbroking/user/v1/getProfile";
    public static final String LOGOUT = "/rest/secure/angelbroking/user/v1/logout";
    public static final String RMS = "/rest/secure/angelbroking/user/v1/getRMS";
    public static final String HOLDINGS = "/rest/secure/angelbroking/portfolio/v1/getHolding";
    public static final String POSITIONS = "/rest/secure/angelbroking/order/v1/getPosition";
    public static final String ORDER_BOOK = "/rest/secure/angelbroking/order/v1/getOrderBook";

    /** SmartAPI error codes for a missing, invalid or expired token. */
    private static final Set<String> SESSION_ERRORS = Set.of("AG8001", "AG8002", "AG8003");
    private static final Pattern TOKEN_SHAPE = Pattern.compile("^[A-Za-z0-9_\\-]+\\.[A-Za-z0-9_\\-]+\\.[A-Za-z0-9_\\-]*$");
    private static final DateTimeFormatter ORDER_TIME = DateTimeFormatter.ofPattern("dd-MMM-yyyy HH:mm:ss", Locale.ENGLISH);
    private static final Logger log = LoggerFactory.getLogger(AngelOneBrokerAdapter.class);

    /** Angel One order-book {@code status} values (lower case) that map onto TradeFlow's states. */
    private static final Map<String, String> STATUS = Map.of(
            "complete", LiveOrderStatus.FILLED,
            "rejected", LiveOrderStatus.REJECTED,
            "cancelled", LiveOrderStatus.CANCELLED,
            "open", LiveOrderStatus.OPEN,
            "open pending", LiveOrderStatus.OPEN,
            "trigger pending", LiveOrderStatus.OPEN,
            "modified", LiveOrderStatus.OPEN);
    private static final Map<String, String> TYPE = Map.of(
            "MARKET", "MARKET", "LIMIT", "LIMIT", "STOPLOSS_LIMIT", "STOP_LIMIT", "STOPLOSS_MARKET", "STOP");

    private final AngelOneProperties properties;
    private final RestClient http;
    private final JsonMapper json;
    private final Clock clock;
    private final RateGuard account;

    public AngelOneBrokerAdapter(AngelOneProperties properties, JsonMapper json, Clock clock) {
        properties.requireComplete();
        this.properties = properties;
        this.json = json;
        this.clock = clock;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.connectTimeout());
        factory.setReadTimeout(properties.readTimeout());
        this.http = RestClient.builder().requestFactory(factory).build();
        // A local backstop against runaway loops, not Angel One's own limits (Angel One enforces those
        // and answers 429, mapped below). The dashboard needs at most two reads at once.
        this.account = new RateGuard("Angel One", "account data", 10, 180, clock);
    }

    @Override public String code() { return "angelone"; }

    @Override public boolean enabled() { return true; }

    @Override public boolean ordersEnabled() { return false; }

    // ------------------------------------------------------------------ authorisation

    @Override
    public String authorizationUrl(String state, String codeChallenge) {
        // SmartAPI publisher login has no PKCE; the single-use, user-bound state protects the redirect.
        return UriComponentsBuilder.fromUriString(properties.loginUrl())
                .queryParam("api_key", properties.apiKey())
                .queryParam("state", state)
                .encode().build().toUriString();
    }

    /** {@code code} is the {@code auth_token} Angel One appended to the redirect. */
    @Override
    public BrokerSession exchangeCode(String code, String codeVerifier) {
        if (code == null || code.length() > 2500 || !TOKEN_SHAPE.matcher(code).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_BROKER_TOKEN",
                    "Angel One did not return a usable login token. Start linking again.");
        }
        JsonNode profile = call(() -> http.get().uri(properties.apiUrl() + PROFILE), code);
        String clientCode = text(profile.path("data"), "clientcode");
        if (clientCode == null || clientCode.isBlank()) {
            throw brokerError(HttpStatus.BAD_GATEWAY, "Angel One did not return your client code");
        }
        return new BrokerSession(code, null, expiry(code, clock.instant()), clientCode);
    }

    @Override
    public void revoke(String accessToken, String accountRef) {
        if (accountRef == null || accountRef.isBlank()) {
            return; // logout needs the client code; the stored token is erased by the caller regardless
        }
        call(() -> http.post().uri(properties.apiUrl() + LOGOUT).body(Map.of("clientcode", accountRef)), accessToken);
    }

    // ------------------------------------------------------------------ account (read-only)

    @Override
    public Funds funds(String accessToken) {
        JsonNode data = call(() -> http.get().uri(properties.apiUrl() + RMS), accessToken).path("data");
        BigDecimal available = decimal(data, "net");
        if (available == null) {
            available = decimal(data, "availablecash");
        }
        return new Funds(available, decimal(data, "utiliseddebits"), "INR", clock.instant());
    }

    @Override
    public List<Holding> holdings(String accessToken) {
        JsonNode data = call(() -> http.get().uri(properties.apiUrl() + HOLDINGS), accessToken).path("data");
        List<Holding> out = new ArrayList<>();
        for (JsonNode h : rows(data)) {
            String exchange = text(h, "exchange");
            out.add(new Holding(exchange, displaySymbol(text(h, "tradingsymbol")), h.path("quantity").asLong(0),
                    decimal(h, "averageprice"), decimal(h, "ltp")));
        }
        return out;
    }

    /** Net positions for the day, intraday and carry-forward alike; {@code product} tells them apart. */
    @Override
    public List<Position> positions(String accessToken) {
        JsonNode data = call(() -> http.get().uri(properties.apiUrl() + POSITIONS), accessToken).path("data");
        List<Position> out = new ArrayList<>();
        for (JsonNode p : rows(data)) {
            BigDecimal average = decimal(p, "avgnetprice");
            if (average == null) {
                average = decimal(p, "netprice");
            }
            BigDecimal pnl = decimal(p, "pnl");
            if (pnl == null) {
                BigDecimal realised = decimal(p, "realised");
                BigDecimal unrealised = decimal(p, "unrealised");
                pnl = realised == null && unrealised == null ? null
                        : (realised == null ? BigDecimal.ZERO : realised).add(unrealised == null ? BigDecimal.ZERO : unrealised);
            }
            out.add(new Position(text(p, "exchange"), displaySymbol(text(p, "tradingsymbol")), text(p, "producttype"),
                    longValue(p, "netqty"), average, pnl));
        }
        return out;
    }

    @Override
    public List<BrokerOrder> orders(String accessToken) {
        JsonNode data = call(() -> http.get().uri(properties.apiUrl() + ORDER_BOOK), accessToken).path("data");
        List<BrokerOrder> out = new ArrayList<>();
        for (JsonNode o : rows(data)) {
            long qty = longValue(o, "quantity");
            long filled = longValue(o, "filledshares");
            String raw = text(o, "status");
            String status = raw == null ? null : STATUS.get(raw.trim().toLowerCase(Locale.ROOT));
            if (LiveOrderStatus.OPEN.equals(status) && filled > 0 && filled < qty) {
                status = LiveOrderStatus.PARTIALLY_FILLED;
            }
            BigDecimal average = decimal(o, "averageprice");
            String type = text(o, "ordertype");
            out.add(new BrokerOrder(text(o, "orderid"), null, text(o, "exchange"), displaySymbol(text(o, "tradingsymbol")),
                    text(o, "transactiontype"), type == null ? "UNKNOWN" : TYPE.getOrDefault(type, type),
                    qty, filled, decimal(o, "price"), average != null && average.signum() > 0 ? average : null,
                    status != null ? status : "ANGELONE_STATUS_" + (raw == null ? "NONE" : raw.trim().toUpperCase(Locale.ROOT).replace(' ', '_')),
                    emptyToNull(text(o, "text")), orderTime(text(o, "updatetime"))));
        }
        return out;
    }

    @Override
    public List<Quote> quotes(String accessToken, List<String> instruments) {
        throw new ApiException(HttpStatus.NOT_IMPLEMENTED, "QUOTES_NOT_AVAILABLE",
                "Live quotes from Angel One are not available in TradeFlow yet.");
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
                "Your Angel One account is linked read-only. Placing and cancelling real orders from TradeFlow is not "
                        + "enabled yet; use Angel One directly to manage orders.");
    }

    // ------------------------------------------------------------------ HTTP

    /**
     * Sends the request with SmartAPI's headers and returns the JSON body when Angel One says
     * {@code "status": true}. Everything else becomes an {@link ApiException} with a stable code;
     * Angel One's message is passed on, tokens never.
     */
    private JsonNode call(Supplier<RestClient.RequestHeadersSpec<?>> request, String accessToken) {
        account.acquire();
        Raw raw;
        try {
            raw = request.get()
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .header("Accept", MediaType.APPLICATION_JSON_VALUE)
                    .header("X-UserType", "USER")
                    .header("X-SourceID", "WEB")
                    .header("X-ClientLocalIP", properties.clientLocalIp())
                    .header("X-ClientPublicIP", properties.clientPublicIp())
                    .header("X-MACAddress", properties.macAddress())
                    .header("X-PrivateKey", properties.apiKey())
                    .exchange((req, res) -> new Raw(res.getStatusCode().value(),
                            new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8)));
        } catch (RestClientException e) {
            // The cause (timeout, reset, TLS...) tells these apart; its message holds the URL only, never a token.
            Throwable cause = e.getMostSpecificCause();
            log.warn("Angel One request failed: {} caused by {}: {}", e.getClass().getSimpleName(),
                    cause.getClass().getSimpleName(), cause.getMessage());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "BROKER_UNAVAILABLE", cause instanceof SocketTimeoutException
                    ? "Angel One did not respond in time. Try again shortly."
                    : "Angel One could not be reached. Try again shortly.");
        }
        JsonNode body;
        try {
            body = json.readTree(raw.body());
        } catch (JacksonException e) {
            log.warn("Angel One returned a non-JSON response (HTTP {})", raw.status());
            if (raw.status() == 429) {
                throw rateLimited();
            }
            throw brokerError(HttpStatus.BAD_GATEWAY, "Angel One returned an unexpected response (HTTP " + raw.status() + ")");
        }
        JsonNode ok = body.path("status");
        if (raw.status() < 400 && (ok.asBoolean(false) || "true".equalsIgnoreCase(ok.asString("")))) {
            return body;
        }
        String errorCode = text(body, "errorcode");
        String message = text(body, "message");
        log.info("Angel One refused a request: HTTP {} errorcode {}", raw.status(), errorCode);
        if (raw.status() == 401 || raw.status() == 403 || (errorCode != null && SESSION_ERRORS.contains(errorCode))) {
            throw new ApiException(HttpStatus.CONFLICT, "BROKER_SESSION_EXPIRED",
                    "Your Angel One session has ended. Disconnect and link your account again.");
        }
        if (raw.status() == 429) {
            throw rateLimited();
        }
        throw brokerError(HttpStatus.BAD_GATEWAY, "Angel One refused the request"
                + (errorCode == null || errorCode.isBlank() ? "" : " (" + truncate(errorCode) + ")")
                + (message == null || message.isBlank() ? "" : ": " + truncate(message)));
    }

    private record Raw(int status, String body) {
    }

    private static ApiException rateLimited() {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "BROKER_RATE_LIMITED",
                "Angel One is rate-limiting requests. Wait a minute and try again.");
    }

    private static ApiException brokerError(HttpStatus status, String message) {
        return new ApiException(status, "BROKER_ERROR", message);
    }

    // ------------------------------------------------------------------ helpers

    /**
     * When TradeFlow stops using the token: its own JWT {@code exp}, never more than 24 hours away;
     * 6 hours if the expiry cannot be read. The signature is not checked here: Angel One does that on
     * every call, and {@link #exchangeCode} has just made one.
     */
    static Instant expiry(String jwt, Instant now) {
        Instant cap = now.plus(MAX_SESSION);
        try {
            String[] parts = jwt.split("\\.");
            byte[] payload = Base64.getUrlDecoder().decode(parts[1]);
            long exp = JsonMapper.builder().build().readTree(payload).path("exp").asLong(0);
            if (exp > 0) {
                Instant at = Instant.ofEpochSecond(exp);
                return at.isAfter(cap) ? cap : at;
            }
        } catch (RuntimeException e) {
            // unreadable payload: fall through to the short default
        }
        return now.plus(FALLBACK_SESSION);
    }

    /** "SBIN-EQ" → "SBIN" (cash equity series); other series, futures and options are kept as given. */
    static String displaySymbol(String tradingSymbol) {
        if (tradingSymbol == null) {
            return null;
        }
        return tradingSymbol.endsWith("-EQ") ? tradingSymbol.substring(0, tradingSymbol.length() - 3) : tradingSymbol;
    }

    static Instant orderTime(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(s.trim(), ORDER_TIME).atZone(IST).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** SmartAPI returns {@code "data": null} rather than an empty list when there is nothing. */
    private static Iterable<JsonNode> rows(JsonNode data) {
        return data != null && data.isArray() ? data : List.of();
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v == null || v.isNull() ? null : v.asString();
    }

    /** Numbers arrive as JSON numbers or as strings ("4663.4100"). */
    private static BigDecimal decimal(JsonNode node, String field) {
        String s = text(node, field);
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static long longValue(JsonNode node, String field) {
        BigDecimal d = decimal(node, field);
        return d == null ? 0 : d.longValue();
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static String truncate(String s) {
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }
}
