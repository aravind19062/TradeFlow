package com.luffy.tradeflow_live_trading_service.broker.angelone;

import com.luffy.tradeflow.common.web.ApiException;
import com.luffy.tradeflow_live_trading_service.broker.BrokerAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static com.luffy.tradeflow_live_trading_service.broker.angelone.FakeAngelOneServer.TOKEN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AngelOneBrokerAdapterTest {

    FakeAngelOneServer server;
    AngelOneBrokerAdapter adapter;

    @BeforeEach
    void start() {
        server = new FakeAngelOneServer();
        adapter = adapter(Duration.ofSeconds(5));
    }

    @AfterEach
    void stop() {
        server.close();
    }

    AngelOneBrokerAdapter adapter(Duration readTimeout) {
        return new AngelOneBrokerAdapter(new AngelOneProperties("smartapi-KEY-1", "https://smartapi.angelone.in/publisher-login",
                server.apiUrl(), "10.0.0.5", "203.0.113.7", "02:42:ac:11:00:02", Duration.ofSeconds(2), readTimeout),
                JsonMapper.builder().build(), Clock.systemUTC());
    }

    static String code(ApiException e) {
        return e.getCode();
    }

    // ------------------------------------------------------------------ configuration

    @Test
    void refusesToStartWithoutApiKeyOrWithMalformedClientHeaders() {
        assertThatThrownBy(() -> new AngelOneProperties(" ", null, null, null, null, null, null, null).requireComplete())
                .hasMessageContaining("TRADEFLOW_ANGELONE_API_KEY");
        assertThatThrownBy(() -> new AngelOneProperties("k", null, null, "not-an-ip", null, null, null, null).requireComplete())
                .hasMessageContaining("IPv4");
        assertThatThrownBy(() -> new AngelOneProperties("k", null, null, null, null, "zz:zz", null, null).requireComplete())
                .hasMessageContaining("MAC");
        AngelOneProperties defaults = new AngelOneProperties("k", null, null, null, null, null, null, null);
        defaults.requireComplete();
        assertThat(defaults.apiUrl()).isEqualTo("https://apiconnect.angelone.in");
        assertThat(defaults.loginUrl()).isEqualTo("https://smartapi.angelone.in/publisher-login");
        assertThat(defaults.toString()).doesNotContain("k,").contains("<redacted>");
    }

    // ------------------------------------------------------------------ linking

    @Test
    void authorizationUrlIsAngelOnesPublisherLoginWithApiKeyAndState() {
        String url = adapter.authorizationUrl("state-123", "ignored-challenge");
        var parsed = UriComponentsBuilder.fromUriString(url).build();
        assertThat(url).startsWith("https://smartapi.angelone.in/publisher-login?");
        assertThat(parsed.getQueryParams().getFirst("api_key")).isEqualTo("smartapi-KEY-1");
        assertThat(parsed.getQueryParams().getFirst("state")).isEqualTo("state-123");
        assertThat(server.requests).isEmpty(); // nothing is sent to Angel One to build the URL
    }

    @Test
    void exchangeVerifiesTheTokenWithGetProfileAndKeepsOnlyTheClientCode() {
        BrokerAdapter.BrokerSession session = adapter.exchangeCode(TOKEN, "unused-verifier");
        assertThat(session.accountRef()).isEqualTo("A123456");
        assertThat(session.accessToken()).isEqualTo(TOKEN);
        assertThat(session.refreshToken()).isNull();
        assertThat(session.toString()).doesNotContain(TOKEN);
        // The token's own exp (8 h) is used.
        assertThat(session.expiresAt()).isBetween(Instant.now().plus(Duration.ofHours(7)), Instant.now().plus(Duration.ofHours(9)));

        var profile = server.requestsTo(AngelOneBrokerAdapter.PROFILE);
        assertThat(profile).singleElement().satisfies(r -> {
            assertThat(r.method()).isEqualTo("GET");
            assertThat(r.header("Authorization")).isEqualTo("Bearer " + TOKEN);
            assertThat(r.header("X-PrivateKey")).isEqualTo("smartapi-KEY-1");
            assertThat(r.header("X-UserType")).isEqualTo("USER");
            assertThat(r.header("X-SourceID")).isEqualTo("WEB");
            assertThat(r.header("X-ClientLocalIP")).isEqualTo("10.0.0.5");
            assertThat(r.header("X-ClientPublicIP")).isEqualTo("203.0.113.7");
            assertThat(r.header("X-MACAddress")).isEqualTo("02:42:ac:11:00:02");
        });
    }

    @Test
    void malformedAuthTokensAreRefusedWithoutCallingAngelOne() {
        for (String bad : new String[]{"", "not-a-jwt", "a.b.c d", "x".repeat(3000)}) {
            assertThatThrownBy(() -> adapter.exchangeCode(bad, null)).isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(code(e)).isEqualTo("INVALID_BROKER_TOKEN"));
        }
        assertThat(server.requests).isEmpty();
    }

    @Test
    void anAuthTokenAngelOneRejectsIsAnExpiredSessionNotALink() {
        server.respond(AngelOneBrokerAdapter.PROFILE, 401,
                "{\"status\":false,\"message\":\"Invalid Token\",\"errorcode\":\"AG8001\",\"data\":null}");
        assertThatThrownBy(() -> adapter.exchangeCode(TOKEN, null)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(code(e)).isEqualTo("BROKER_SESSION_EXPIRED"));
    }

    @Test
    void sessionExpiryComesFromTheTokenCappedAt24Hours() {
        Instant now = Instant.parse("2026-11-08T05:00:00Z");
        assertThat(AngelOneBrokerAdapter.expiry(FakeAngelOneServer.jwt(now.plusSeconds(3600).getEpochSecond()), now))
                .isEqualTo(now.plusSeconds(3600));
        assertThat(AngelOneBrokerAdapter.expiry(FakeAngelOneServer.jwt(now.plus(Duration.ofDays(30)).getEpochSecond()), now))
                .isEqualTo(now.plus(Duration.ofHours(24)));
        assertThat(AngelOneBrokerAdapter.expiry("aaa.bbb.ccc", now)).isEqualTo(now.plus(Duration.ofHours(6)));
    }

    @Test
    void logoutSendsTheClientCodeAndIsSkippedWithoutOne() {
        adapter.revoke(TOKEN, "A123456");
        assertThat(server.requestsTo(AngelOneBrokerAdapter.LOGOUT)).singleElement().satisfies(r -> {
            assertThat(r.method()).isEqualTo("POST");
            assertThat(r.body()).contains("\"clientcode\":\"A123456\"");
        });
        adapter.revoke(TOKEN, null);
        assertThat(server.requestsTo(AngelOneBrokerAdapter.LOGOUT)).hasSize(1);
    }

    // ------------------------------------------------------------------ response mapping

    @Test
    void fundsMapNetAndUtilisedDebits() {
        BrokerAdapter.Funds f = adapter.funds(TOKEN);
        assertThat(f.available()).isEqualByComparingTo("18116.81");
        assertThat(f.used()).isEqualByComparingTo("12554.55");
        assertThat(f.currency()).isEqualTo("INR");
    }

    @Test
    void fundsFallBackToAvailableCashWhenNetIsMissing() {
        server.respond(AngelOneBrokerAdapter.RMS, 200,
                "{\"status\":true,\"message\":\"SUCCESS\",\"errorcode\":\"\",\"data\":{\"availablecash\":\"4663.41\",\"utiliseddebits\":\"0\"}}");
        assertThat(adapter.funds(TOKEN).available()).isEqualByComparingTo("4663.41");
    }

    @Test
    void holdingsStripTheEquitySeriesAndAcceptNumbersOrStrings() {
        List<BrokerAdapter.Holding> h = adapter.holdings(TOKEN);
        assertThat(h).containsExactly(
                new BrokerAdapter.Holding("NSE", "TATASTEEL", 2, new BigDecimal("111.87"), new BigDecimal("130.15")),
                new BrokerAdapter.Holding("NSE", "GOLDBEES", 10, new BigDecimal("70.10"), new BigDecimal("71.62")));
    }

    @Test
    void positionsIncludeIntradayWithProductTypeAndShortQuantities() {
        List<BrokerAdapter.Position> p = adapter.positions(TOKEN);
        assertThat(p).hasSize(2);
        assertThat(p.get(0)).isEqualTo(new BrokerAdapter.Position("NSE", "SBIN", "INTRADAY", -3,
                new BigDecimal("804.10"), new BigDecimal("6.30")));
        // No "pnl" field: realised + unrealised.
        assertThat(p.get(1).product()).isEqualTo("DELIVERY");
        assertThat(p.get(1).pnl()).isEqualByComparingTo("-12.50");
    }

    @Test
    void emptyBooksComeBackAsNullDataAndMapToEmptyLists() {
        String empty = "{\"status\":true,\"message\":\"SUCCESS\",\"errorcode\":\"\",\"data\":null}";
        server.respond(AngelOneBrokerAdapter.HOLDINGS, 200, empty);
        server.respond(AngelOneBrokerAdapter.POSITIONS, 200, empty);
        server.respond(AngelOneBrokerAdapter.ORDER_BOOK, 200, empty);
        assertThat(adapter.holdings(TOKEN)).isEmpty();
        assertThat(adapter.positions(TOKEN)).isEmpty();
        assertThat(adapter.orders(TOKEN)).isEmpty();
    }

    @Test
    void orderBookMapsStatusesTypesFillsAndTimes() {
        List<BrokerAdapter.BrokerOrder> o = adapter.orders(TOKEN);
        assertThat(o).hasSize(3);
        BrokerAdapter.BrokerOrder partial = o.get(0);
        assertThat(partial.brokerOrderId()).isEqualTo("201020000000080");
        assertThat(partial.symbol()).isEqualTo("TATASTEEL");
        assertThat(partial.side()).isEqualTo("BUY");
        assertThat(partial.type()).isEqualTo("LIMIT");
        assertThat(partial.status()).isEqualTo("PARTIALLY_FILLED");
        assertThat(partial.filledQuantity()).isEqualTo(4);
        assertThat(partial.averagePrice()).isEqualByComparingTo("129.95");
        assertThat(partial.clientOrderId()).isNull(); // TradeFlow has placed no orders through Angel One
        assertThat(partial.updatedAt()).isEqualTo(Instant.parse("2026-11-08T04:45:02Z")); // 10:15:02 IST

        BrokerAdapter.BrokerOrder rejected = o.get(1);
        assertThat(rejected.status()).isEqualTo("REJECTED");
        assertThat(rejected.type()).isEqualTo("MARKET");
        assertThat(rejected.averagePrice()).isNull();
        assertThat(rejected.message()).isEqualTo("RMS: margin exceeds");

        // An Angel One status TradeFlow does not know stays visible as such, not guessed.
        assertThat(o.get(2).status()).isEqualTo("ANGELONE_STATUS_AFTER_MARKET_ORDER_REQ_RECEIVED");
        assertThat(o.get(2).type()).isEqualTo("STOP_LIMIT");
    }

    // ------------------------------------------------------------------ failures

    @Test
    void expiredOrInvalidTokensBecomeSessionExpired() {
        for (String errorCode : new String[]{"AG8001", "AG8002", "AG8003"}) {
            server.respond(AngelOneBrokerAdapter.RMS, 200,
                    "{\"status\":false,\"message\":\"Token Expired\",\"errorcode\":\"" + errorCode + "\",\"data\":null}");
            assertThatThrownBy(() -> adapter.funds(TOKEN)).isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(code(e)).isEqualTo("BROKER_SESSION_EXPIRED"));
        }
        server.respond(AngelOneBrokerAdapter.RMS, 403, "{\"status\":false,\"message\":\"Forbidden\",\"errorcode\":\"\"}");
        assertThatThrownBy(() -> adapter.funds(TOKEN)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(code(e)).isEqualTo("BROKER_SESSION_EXPIRED"));
    }

    @Test
    void otherApiErrorsPassAngelOnesMessageOnButNeverTheToken() {
        server.respond(AngelOneBrokerAdapter.HOLDINGS, 200,
                "{\"status\":false,\"message\":\"Something went wrong, please try again\",\"errorcode\":\"AB1004\",\"data\":null}");
        assertThatThrownBy(() -> adapter.holdings(TOKEN)).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(code(e)).isEqualTo("BROKER_ERROR");
            assertThat(e.getMessage()).contains("AB1004").contains("Something went wrong").doesNotContain(TOKEN);
        });
    }

    @Test
    void rateLimitsAndNonJsonResponsesAreReportedClearly() {
        server.respondRaw(AngelOneBrokerAdapter.POSITIONS, 429, "Access denied because of exceeding access rate", "text/plain");
        assertThatThrownBy(() -> adapter.positions(TOKEN)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(code(e)).isEqualTo("BROKER_RATE_LIMITED"));
        server.respondRaw(AngelOneBrokerAdapter.POSITIONS, 502, "<html>Bad gateway</html>", "text/html");
        assertThatThrownBy(() -> adapter.positions(TOKEN)).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(code(e)).isEqualTo("BROKER_ERROR");
            assertThat(e.getMessage()).contains("unexpected response");
        });
    }

    @Test
    void aSlowAngelOneIsATimeoutNotAHang() {
        AngelOneBrokerAdapter impatient = adapter(Duration.ofMillis(300));
        server.delay(1500);
        assertThatThrownBy(() -> impatient.funds(TOKEN)).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(code(e)).isEqualTo("BROKER_UNAVAILABLE");
            assertThat(e.getMessage()).contains("did not respond in time");
        });
    }

    @Test
    void theLocalRateGuardRefusesBeforeAngelOneIsFlooded() {
        AngelOneBrokerAdapter fixed = new AngelOneBrokerAdapter(new AngelOneProperties("k", null, server.apiUrl(),
                null, null, null, null, null), JsonMapper.builder().build(),
                Clock.fixed(Instant.parse("2026-11-08T05:00:00Z"), ZoneOffset.UTC));
        for (int i = 0; i < 10; i++) {
            fixed.funds(TOKEN);
        }
        int sent = server.requests.size();
        assertThatThrownBy(() -> fixed.funds(TOKEN)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(code(e)).isEqualTo("BROKER_RATE_LIMITED"));
        assertThat(server.requests).hasSize(sent);
    }

    // ------------------------------------------------------------------ never an order

    @Test
    void ordersAndQuotesAreRefusedWithoutContactingAngelOne() {
        var request = new BrokerAdapter.OrderRequest("L-1", "NSE", "TCS", "BUY", "LIMIT", 1, BigDecimal.TEN, null);
        assertThatThrownBy(() -> adapter.placeOrder(TOKEN, request)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(code(e)).isEqualTo("LIVE_ORDERS_NOT_ENABLED"));
        assertThatThrownBy(() -> adapter.cancelOrder(TOKEN, "1")).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(code(e)).isEqualTo("LIVE_ORDERS_NOT_ENABLED"));
        assertThatThrownBy(() -> adapter.quotes(TOKEN, List.of("NSE:TCS"))).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(code(e)).isEqualTo("QUOTES_NOT_AVAILABLE"));
        assertThat(adapter.ordersEnabled()).isFalse();
        assertThat(server.requests).isEmpty();
    }
}
