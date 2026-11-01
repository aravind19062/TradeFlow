package com.luffy.tradeflow_live_trading_service.broker.fyers;

import com.luffy.tradeflow.common.web.ApiException;
import com.luffy.tradeflow_live_trading_service.broker.BrokerAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static com.luffy.tradeflow_live_trading_service.broker.fyers.FakeFyersServer.TOKEN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FyersBrokerAdapterTest {

    // 10:00 IST on 1 Nov 2026
    private static final Instant NOW = Instant.parse("2026-11-01T04:30:00Z");

    private final FakeFyersServer fyers = new FakeFyersServer();
    private final JsonMapper json = JsonMapper.builder().build();

    private FyersBrokerAdapter adapter() {
        return adapter(fyers.apiUrl());
    }

    private FyersBrokerAdapter adapter(String apiUrl) {
        return new FyersBrokerAdapter(new FyersProperties("XY12345-100", "app-SECRET-key", "http://localhost:3000/live/broker/callback",
                apiUrl, fyers.dataUrl(), null, null), json, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void stop() {
        fyers.close();
    }

    @Test
    void authorisationUrlIsFyersOwnLoginPageWithStateAndNoSecret() {
        URI url = URI.create(adapter().authorizationUrl("state-123", "ignored-challenge"));
        assertThat(url.getPath()).isEqualTo("/api/v3/generate-authcode");
        assertThat(url.getQuery()).contains("client_id=XY12345-100", "response_type=code", "state=state-123",
                "redirect_uri=http://localhost:3000/live/broker/callback");
        assertThat(url.toString()).doesNotContain("SECRET");
        assertThat(fyers.requests).isEmpty(); // building the link calls nothing
    }

    @Test
    void exchangesTheCodeWithAppIdHashKeepsNoRefreshTokenAndExpiresAtTheDailyCutoff() {
        BrokerAdapter.BrokerSession session = adapter().exchangeCode("auth-code-1", "verifier");

        var exchange = fyers.requestsTo("/api/v3/validate-authcode").get(0);
        assertThat(exchange.method()).isEqualTo("POST");
        // SHA-256("XY12345-100:app-SECRET-key"), not the secret itself
        assertThat(exchange.body()).contains("\"grant_type\":\"authorization_code\"", "\"code\":\"auth-code-1\"",
                "\"appIdHash\":\"" + adapter().appIdHash() + "\"").doesNotContain("app-SECRET-key");
        assertThat(adapter().appIdHash()).hasSize(64);
        assertThat(fyers.requestsTo("/api/v3/profile").get(0).authorization()).isEqualTo("XY12345-100:" + TOKEN);

        assertThat(session.accessToken()).isEqualTo(TOKEN);
        assertThat(session.refreshToken()).as("refreshing needs the user's PIN; nothing is kept").isNull();
        assertThat(session.accountRef()).isEqualTo("XA00001");
        assertThat(session.expiresAt()).isEqualTo(Instant.parse("2026-11-02T01:00:00Z")); // 06:30 IST next day
        assertThat(session.toString()).doesNotContain(TOKEN).doesNotContain("PAN").doesNotContain("AB***");
    }

    @Test
    void dailyExpiryIsTheNext0630InIndia() {
        assertThat(FyersBrokerAdapter.nextExpiry(Instant.parse("2026-11-01T23:00:00Z"))) // 04:30 IST
                .isEqualTo(Instant.parse("2026-11-02T01:00:00Z"));
        assertThat(FyersBrokerAdapter.nextExpiry(Instant.parse("2026-11-02T01:00:00Z"))) // exactly 06:30 IST
                .isEqualTo(Instant.parse("2026-11-03T01:00:00Z"));
    }

    @Test
    void readsFundsHoldingsAndPositions() {
        FyersBrokerAdapter a = adapter();
        BrokerAdapter.Funds funds = a.funds(TOKEN);
        assertThat(funds.available()).isEqualByComparingTo("60335.58");
        assertThat(funds.used()).isEqualByComparingTo("1350.5");
        assertThat(funds.currency()).isEqualTo("INR");

        assertThat(a.holdings(TOKEN)).containsExactly(
                new BrokerAdapter.Holding("NSE", "IDEA", 2, new BigDecimal("9.72"), new BigDecimal("11.85")));
        assertThat(a.positions(TOKEN)).containsExactly(
                new BrokerAdapter.Position("NSE", "SBIN", -3, new BigDecimal("590.5"), new BigDecimal("12.3")));
        assertThat(fyers.requests).allSatisfy(r -> assertThat(r.authorization()).isEqualTo("XY12345-100:" + TOKEN));
    }

    @Test
    void mapsTheOrderBookOntoTradeFlowStatesAndTags() {
        List<BrokerAdapter.BrokerOrder> orders = adapter().orders(TOKEN);
        assertThat(orders).hasSize(3);

        BrokerAdapter.BrokerOrder partial = orders.get(0);
        assertThat(partial.brokerOrderId()).isEqualTo("23080444447604");
        assertThat(partial.clientOrderId()).isEqualTo("L-TEST-0001"); // user tag "1:..."
        assertThat(partial.status()).isEqualTo("PARTIALLY_FILLED");  // pending with 4 of 10 filled
        assertThat(partial.side()).isEqualTo("BUY");
        assertThat(partial.type()).isEqualTo("LIMIT");
        assertThat(partial.filledQuantity()).isEqualTo(4);
        assertThat(partial.averagePrice()).isEqualByComparingTo("8.05");
        assertThat(partial.updatedAt()).isEqualTo(Instant.parse("2023-08-02T07:31:42Z")); // 13:01:42 IST

        BrokerAdapter.BrokerOrder rejected = orders.get(1);
        assertThat(rejected.clientOrderId()).as("Fyers' own tag is not TradeFlow's").isNull();
        assertThat(rejected.status()).isEqualTo("REJECTED");
        assertThat(rejected.side()).isEqualTo("SELL");
        assertThat(rejected.type()).isEqualTo("MARKET");
        assertThat(rejected.averagePrice()).isNull();
        assertThat(rejected.message()).isEqualTo("RMS: insufficient funds");

        assertThat(orders.get(2).status()).as("unused status code: not a state reconciliation will apply")
                .isEqualTo("FYERS_STATUS_3");
    }

    @Test
    void quotesUseFyersSymbolsAndSkipUnknownOnes() {
        List<BrokerAdapter.Quote> quotes = adapter().quotes(TOKEN, List.of("NSE:SBIN", "NSE:NOPE"));
        assertThat(fyers.requestsTo("/data/quotes").get(0).query()).isEqualTo("symbols=NSE:SBIN-EQ,NSE:NOPE-EQ");
        assertThat(quotes).containsExactly(new BrokerAdapter.Quote("NSE", "SBIN", new BigDecimal("590.5"),
                new BigDecimal("590.45"), new BigDecimal("590.6"), Instant.ofEpochSecond(1691020800), "FYERS"));
    }

    @Test
    void ordersAreRefusedWithoutAnyCallToFyers() {
        FyersBrokerAdapter a = adapter();
        assertThat(a.enabled()).isTrue();
        assertThat(a.ordersEnabled()).isFalse();
        var request = new BrokerAdapter.OrderRequest("L-1", "NSE", "TCS", "BUY", "MARKET", 1, null, null);
        assertThatThrownBy(() -> a.placeOrder(TOKEN, request)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getCode()).isEqualTo("LIVE_ORDERS_NOT_ENABLED"));
        assertThatThrownBy(() -> a.cancelOrder(TOKEN, "23080444447604")).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getCode()).isEqualTo("LIVE_ORDERS_NOT_ENABLED"));
        assertThat(fyers.requests).isEmpty();
    }

    @Test
    void mapsFyersErrorsToStableCodesWithoutLeakingTheToken() {
        FyersBrokerAdapter a = adapter();
        fyers.respond("/api/v3/funds", 401, "{\"s\":\"error\",\"code\":-16,\"message\":\"Could not authenticate the user\"}");
        assertThatThrownBy(() -> a.funds(TOKEN)).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.getCode()).isEqualTo("BROKER_SESSION_EXPIRED");
            assertThat(e.getMessage()).doesNotContain(TOKEN);
        });
        fyers.respond("/api/v3/holdings", 429, "{\"s\":\"error\",\"code\":-429,\"message\":\"request limit reached\"}");
        assertThatThrownBy(() -> a.holdings(TOKEN)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getCode()).isEqualTo("BROKER_RATE_LIMITED"));
        fyers.respond("/api/v3/positions", 400, "{\"s\":\"error\",\"code\":-99,\"message\":\"Something went wrong\"}");
        assertThatThrownBy(() -> a.positions(TOKEN)).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.getCode()).isEqualTo("BROKER_ERROR");
            assertThat(e.getMessage()).contains("-99").contains("Something went wrong").doesNotContain(TOKEN);
        });
        fyers.respond("/api/v3/orders", 200, "<html>maintenance</html>");
        assertThatThrownBy(() -> a.orders(TOKEN)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getCode()).isEqualTo("BROKER_ERROR"));
    }

    @Test
    void unreachableFyersIsReportedAsUnavailable() {
        FyersBrokerAdapter a = adapter("http://127.0.0.1:1/api/v3");
        assertThatThrownBy(() -> a.funds(TOKEN)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getCode()).isEqualTo("BROKER_UNAVAILABLE"));
    }

    @Test
    void staysUnderTheMarketDataRateLimitByRefusingLocally() {
        FyersBrokerAdapter a = adapter(); // fixed clock: every call is in the same second
        for (int i = 0; i < 4; i++) {
            a.quotes(TOKEN, List.of("NSE:SBIN"));
        }
        assertThatThrownBy(() -> a.quotes(TOKEN, List.of("NSE:SBIN"))).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getCode()).isEqualTo("BROKER_RATE_LIMITED"));
        assertThat(fyers.requestsTo("/data/quotes")).hasSize(4);
    }

    @Test
    void refusesIncompleteOrInsecureConfiguration() {
        assertThatThrownBy(() -> new FyersBrokerAdapter(new FyersProperties("XY12345-100", "", "https://x.example/cb",
                null, null, null, null), json, Clock.systemUTC())).hasMessageContaining("TRADEFLOW_FYERS_SECRET_KEY");
        assertThatThrownBy(() -> new FyersBrokerAdapter(new FyersProperties("XY12345-100", "s", "http://x.example/cb",
                null, null, null, null), json, Clock.systemUTC())).hasMessageContaining("https");
        assertThat(new FyersProperties("XY12345-100", "app-SECRET-key", "https://x.example/cb", null, null, null, null)
                .toString()).doesNotContain("app-SECRET-key");
    }
}
