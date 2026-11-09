package com.luffy.tradeflow_live_trading_service;

import com.luffy.tradeflow.common.testsupport.TestJwts;
import com.luffy.tradeflow_live_trading_service.broker.BrokerAdapter;
import com.luffy.tradeflow_live_trading_service.broker.fyers.FakeFyersServer;
import com.luffy.tradeflow_live_trading_service.broker.fyers.FyersBrokerAdapter;
import com.luffy.tradeflow_live_trading_service.platform.PlatformChecks;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static com.luffy.tradeflow_live_trading_service.broker.fyers.FakeFyersServer.TOKEN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 6 as configured with Fyers: a user links their account through Fyers' login page, reads
 * their funds, holdings, positions and order book, and cannot preview or place a real order.
 */
@SpringBootTest(properties = {
        "tradeflow.live.broker=fyers",
        "tradeflow.live.fyers.app-id=XY12345-100",
        "tradeflow.live.fyers.secret-key=app-SECRET-key",
        "tradeflow.live.fyers.redirect-uri=http://localhost:3000/live/broker/callback"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FyersReadOnlyTest {

    static final FakeFyersServer FYERS = new FakeFyersServer();

    @DynamicPropertySource
    static void fyersUrls(DynamicPropertyRegistry registry) {
        registry.add("tradeflow.live.fyers.api-url", FYERS::apiUrl);
        registry.add("tradeflow.live.fyers.data-url", FYERS::dataUrl);
    }

    @AfterAll
    static void stop() {
        FYERS.close();
    }

    @Autowired MockMvc mvc;
    @Autowired BrokerAdapter broker;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @MockitoBean PlatformChecks platform;

    @Test
    void linkReadAndNeverOrder() throws Exception {
        assertThat(broker).isInstanceOf(FyersBrokerAdapter.class);
        when(platform.eligibility(anyLong())).thenAnswer(inv -> new PlatformChecks.Eligibility(inv.getArgument(0),
                "QUALIFIED", true, List.of(), 1, Instant.now(), null, null, Instant.now()));
        when(platform.session(anyLong())).thenAnswer(inv -> new PlatformChecks.SessionState(inv.getArgument(0), true, 0, true));
        long userId = 61;
        String access = TestJwts.bearer(TestJwts.withAudience(userId, "tradeflow", Map.of("tv", 0)));
        String stepUp = TestJwts.bearer(TestJwts.withAudience(userId, "tradeflow-live",
                Map.of("amr", List.of("pwd", "otp"), "auth_time", Instant.now().getEpochSecond(), "tv", 0)));

        mvc.perform(get("/api/live/status").header("Authorization", access)).andExpect(status().isOk())
                .andExpect(jsonPath("$.brokerConfigured").value(true))
                .andExpect(jsonPath("$.ordersEnabled").value(false))
                .andExpect(jsonPath("$.broker").value("fyers"))
                .andExpect(jsonPath("$.notice").value(org.hamcrest.Matchers.startsWith("Read-only")));

        // 1. Connect: TradeFlow returns Fyers' own login URL; nothing is sent to Fyers yet.
        String started = mvc.perform(post("/api/live/broker/connect").header("Authorization", stepUp))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String url = json.readTree(started).get("authorizationUrl").asString();
        assertThat(url).startsWith(FYERS.apiUrl() + "/generate-authcode?client_id=XY12345-100").doesNotContain("SECRET");
        assertThat(FYERS.requests).isEmpty();
        String state = UriComponentsBuilder.fromUriString(url).build().getQueryParams().getFirst("state");

        // 2. Fyers redirects back with auth_code + state; the callback exchanges it.
        String linked = mvc.perform(post("/api/live/broker/callback").header("Authorization", stepUp)
                        .contentType("application/json").content("{\"state\":\"" + state + "\",\"code\":\"auth-code-1\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.broker").value("fyers"))
                .andExpect(jsonPath("$.accountRef").value("XA00001"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn().getResponse().getContentAsString();
        assertThat(linked).doesNotContain(TOKEN).doesNotContain("refresh");
        // The state is single use.
        mvc.perform(post("/api/live/broker/callback").header("Authorization", stepUp)
                        .contentType("application/json").content("{\"state\":\"" + state + "\",\"code\":\"auth-code-1\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_STATE"));

        // The token is stored encrypted only, and no refresh token is kept.
        assertThat(jdbc.queryForList("SELECT access_token_ct, refresh_token_ct FROM broker_credentials"))
                .singleElement().satisfies(row -> {
                    assertThat((String) row.get("access_token_ct")).isNotBlank().doesNotContain(TOKEN);
                    assertThat(row.get("refresh_token_ct")).isNull();
                });

        // 3. Read-only views with an ordinary access token.
        String funds = mvc.perform(get("/api/live/broker/funds").header("Authorization", access))
                .andExpect(status().isOk()).andExpect(jsonPath("$.available").value(60335.58))
                .andReturn().getResponse().getContentAsString();
        mvc.perform(get("/api/live/broker/holdings").header("Authorization", access)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].symbol").value("IDEA"));
        mvc.perform(get("/api/live/broker/positions").header("Authorization", access)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].quantity").value(-3));
        mvc.perform(get("/api/live/broker/orders").header("Authorization", access)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("PARTIALLY_FILLED"));
        assertThat(funds).doesNotContain(TOKEN);

        // 4. No real order: refused before any limit check, confirmation or broker call.
        int before = FYERS.requests.size();
        String order = "{\"exchange\":\"NSE\",\"symbol\":\"TCS\",\"side\":\"BUY\",\"type\":\"LIMIT\",\"quantity\":1,\"limitPrice\":10}";
        mvc.perform(post("/api/live/orders/preview").header("Authorization", stepUp).contentType("application/json").content(order))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("LIVE_ORDERS_NOT_ENABLED"));
        mvc.perform(post("/api/live/orders").header("Authorization", stepUp).contentType("application/json")
                        .content("{\"confirmationId\":\"00000000-0000-0000-0000-000000000001\",\"clientOrderId\":\"L-TEST-0002\",\"order\":" + order + "}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("LIVE_ORDERS_NOT_ENABLED"));
        assertThat(FYERS.requests).hasSize(before);
        assertThat(FYERS.requests).noneMatch(r -> r.path().contains("order") && !r.method().equals("GET"));

        // 5. Disconnect: Fyers logout with the token, then the stored credential is erased.
        mvc.perform(post("/api/live/broker/disconnect").header("Authorization", stepUp)).andExpect(status().isOk());
        assertThat(FYERS.requestsTo("/api/v3/logout")).singleElement()
                .satisfies(r -> assertThat(r.authorization()).isEqualTo("XY12345-100:" + TOKEN));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM broker_credentials", Integer.class)).isZero();
        JsonNode after = json.readTree(mvc.perform(get("/api/live/status").header("Authorization", access))
                .andReturn().getResponse().getContentAsString());
        assertThat(after.get("connection").isNull()).isTrue();
    }
}
