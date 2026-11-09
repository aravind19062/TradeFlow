package com.luffy.tradeflow_live_trading_service;

import com.luffy.tradeflow.common.testsupport.TestJwts;
import com.luffy.tradeflow_live_trading_service.broker.BrokerAdapter;
import com.luffy.tradeflow_live_trading_service.broker.angelone.AngelOneBrokerAdapter;
import com.luffy.tradeflow_live_trading_service.broker.angelone.FakeAngelOneServer;
import com.luffy.tradeflow_live_trading_service.platform.PlatformChecks;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.luffy.tradeflow_live_trading_service.broker.angelone.FakeAngelOneServer.TOKEN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 6 with Angel One: a qualified user with step-up links their account through Angel One's
 * own login page, reads funds, holdings, positions and the order book, and cannot preview or place a
 * real order. The readiness gate, step-up and audit are the same as for any broker.
 */
@SpringBootTest(properties = {"tradeflow.live.broker=angelone", "tradeflow.live.angelone.api-key=smartapi-KEY-1"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AngelOneReadOnlyTest {

    static final FakeAngelOneServer ANGEL = new FakeAngelOneServer();

    @DynamicPropertySource
    static void angelUrls(DynamicPropertyRegistry registry) {
        registry.add("tradeflow.live.angelone.api-url", ANGEL::apiUrl);
    }

    @AfterAll
    static void stop() {
        ANGEL.close();
    }

    @Autowired MockMvc mvc;
    @Autowired BrokerAdapter broker;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @MockitoBean PlatformChecks platform;

    @BeforeEach
    void qualified() {
        when(platform.eligibility(anyLong())).thenAnswer(inv -> new PlatformChecks.Eligibility(inv.getArgument(0),
                "QUALIFIED", true, List.of(), 1, Instant.now(), null, null, Instant.now()));
        when(platform.session(anyLong())).thenAnswer(inv -> new PlatformChecks.SessionState(inv.getArgument(0), true, 0, true));
    }

    static String access(long userId) {
        return TestJwts.bearer(TestJwts.withAudience(userId, "tradeflow", Map.of("tv", 0)));
    }

    static String stepUp(long userId) {
        return TestJwts.bearer(TestJwts.withAudience(userId, "tradeflow-live",
                Map.of("amr", List.of("pwd", "otp"), "auth_time", Instant.now().getEpochSecond(), "tv", 0)));
    }

    String connect(String stepUp) throws Exception {
        String started = mvc.perform(post("/api/live/broker/connect").header("Authorization", stepUp))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(started).get("authorizationUrl").asString();
    }

    @Test
    void linkReadAndNeverOrder() throws Exception {
        assertThat(broker).isInstanceOf(AngelOneBrokerAdapter.class);
        long userId = 71;
        String access = access(userId);
        String stepUp = stepUp(userId);

        mvc.perform(get("/api/live/status").header("Authorization", access)).andExpect(status().isOk())
                .andExpect(jsonPath("$.brokerConfigured").value(true))
                .andExpect(jsonPath("$.ordersEnabled").value(false))
                .andExpect(jsonPath("$.broker").value("angelone"))
                .andExpect(jsonPath("$.connection").doesNotExist());

        // 1. Connect: TradeFlow returns Angel One's own login page; nothing is sent to Angel One yet.
        int before = ANGEL.requests.size();
        String url = connect(stepUp);
        assertThat(url).startsWith("https://smartapi.angelone.in/publisher-login?api_key=smartapi-KEY-1");
        assertThat(ANGEL.requests).hasSize(before);
        String state = UriComponentsBuilder.fromUriString(url).build().getQueryParams().getFirst("state");
        assertThat(state).isNotBlank();

        // 2. Angel One redirects back with auth_token (+ the state); the callback verifies it via getProfile.
        String linked = mvc.perform(post("/api/live/broker/callback").header("Authorization", stepUp)
                        .contentType("application/json").content("{\"state\":\"" + state + "\",\"code\":\"" + TOKEN + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.broker").value("angelone"))
                .andExpect(jsonPath("$.accountRef").value("A123456"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn().getResponse().getContentAsString();
        assertThat(linked).doesNotContain(TOKEN);
        // The state is single use.
        mvc.perform(post("/api/live/broker/callback").header("Authorization", stepUp)
                        .contentType("application/json").content("{\"state\":\"" + state + "\",\"code\":\"" + TOKEN + "\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_STATE"));

        // The token is stored encrypted only, and no refresh token is kept.
        assertThat(jdbc.queryForList("SELECT access_token_ct, refresh_token_ct FROM broker_credentials c "
                + "JOIN broker_connections b ON b.id = c.connection_id WHERE b.user_id = ?", userId))
                .singleElement().satisfies(row -> {
                    assertThat((String) row.get("access_token_ct")).isNotBlank().doesNotContain(TOKEN);
                    assertThat(row.get("refresh_token_ct")).isNull();
                });

        // 3. Read-only views with an ordinary access token.
        String funds = mvc.perform(get("/api/live/broker/funds").header("Authorization", access))
                .andExpect(status().isOk()).andExpect(jsonPath("$.available").value(18116.81))
                .andReturn().getResponse().getContentAsString();
        mvc.perform(get("/api/live/broker/holdings").header("Authorization", access)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].symbol").value("TATASTEEL"));
        mvc.perform(get("/api/live/broker/positions").header("Authorization", access)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].quantity").value(-3))
                .andExpect(jsonPath("$[0].product").value("INTRADAY"));
        mvc.perform(get("/api/live/broker/orders").header("Authorization", access)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("PARTIALLY_FILLED"));
        assertThat(funds).doesNotContain(TOKEN);

        // 4. No real order: refused before any limit check, confirmation or broker call.
        int sent = ANGEL.requests.size();
        String order = "{\"exchange\":\"NSE\",\"symbol\":\"TCS\",\"side\":\"BUY\",\"type\":\"LIMIT\",\"quantity\":1,\"limitPrice\":10}";
        mvc.perform(post("/api/live/orders/preview").header("Authorization", stepUp).contentType("application/json").content(order))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("LIVE_ORDERS_NOT_ENABLED"));
        mvc.perform(post("/api/live/orders").header("Authorization", stepUp).contentType("application/json")
                        .content("{\"confirmationId\":\"00000000-0000-0000-0000-000000000001\",\"clientOrderId\":\"L-TEST-0003\",\"order\":" + order + "}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("LIVE_ORDERS_NOT_ENABLED"));
        assertThat(ANGEL.requests).hasSize(sent);
        assertThat(ANGEL.requests).allMatch(r -> r.method().equals("GET") || r.path().endsWith("/logout"));

        // 5. Disconnect: Angel One logout with the client code, then the stored credential is erased.
        mvc.perform(post("/api/live/broker/disconnect").header("Authorization", stepUp)).andExpect(status().isOk());
        assertThat(ANGEL.requestsTo(AngelOneBrokerAdapter.LOGOUT)).singleElement().satisfies(r -> {
            assertThat(r.header("Authorization")).isEqualTo("Bearer " + TOKEN);
            assertThat(r.body()).contains("A123456");
        });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM broker_credentials c JOIN broker_connections b "
                + "ON b.id = c.connection_id WHERE b.user_id = ?", Integer.class, userId)).isZero();
        JsonNode after = json.readTree(mvc.perform(get("/api/live/status").header("Authorization", access))
                .andReturn().getResponse().getContentAsString());
        assertThat(after.get("connection").isNull()).isTrue();
    }

    @Test
    void linkingNeedsStepUpAndAnAuthTokenAngelOneAccepts() throws Exception {
        long userId = 72;
        // An ordinary session token is not enough to start linking.
        mvc.perform(post("/api/live/broker/connect").header("Authorization", access(userId)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("STEP_UP_REQUIRED"));

        String stepUp = stepUp(userId);
        String state = UriComponentsBuilder.fromUriString(connect(stepUp)).build().getQueryParams().getFirst("state");
        // Missing state (e.g. Angel One not returning it): refused, never linked without CSRF protection.
        mvc.perform(post("/api/live/broker/callback").header("Authorization", stepUp)
                        .contentType("application/json").content("{\"state\":\"\",\"code\":\"" + TOKEN + "\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_CALLBACK"));
        // A token Angel One rejects: no connection, no stored credential.
        ANGEL.respond(AngelOneBrokerAdapter.PROFILE, 401,
                "{\"status\":false,\"message\":\"Invalid Token\",\"errorcode\":\"AG8001\",\"data\":null}");
        try {
            mvc.perform(post("/api/live/broker/callback").header("Authorization", stepUp)
                            .contentType("application/json").content("{\"state\":\"" + state + "\",\"code\":\"" + TOKEN + "\"}"))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BROKER_SESSION_EXPIRED"));
        } finally {
            ANGEL.respond(AngelOneBrokerAdapter.PROFILE, 200, """
                    {"status":true,"message":"SUCCESS","errorcode":"","data":{"clientcode":"A123456"}}""");
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM broker_credentials c JOIN broker_connections b "
                + "ON b.id = c.connection_id WHERE b.user_id = ?", Integer.class, userId)).isZero();
        mvc.perform(get("/api/live/status").header("Authorization", access(userId)))
                .andExpect(jsonPath("$.connection").doesNotExist());
    }

    @Test
    void aLinkLeftFromAnotherBrokerIsIgnoredAndRetiredWhenLinkingAngelOne() throws Exception {
        long userId = 73;
        UUID old = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        Timestamp later = Timestamp.from(Instant.now().plusSeconds(3600));
        jdbc.update("INSERT INTO broker_connections (id, user_id, broker_code, status, broker_account_ref, created_at, "
                + "connected_at, expires_at, version) VALUES (?, ?, 'fyers', 'ACTIVE', 'XA00001', ?, ?, ?, 0)", old, userId, now, now, later);
        jdbc.update("INSERT INTO broker_credentials (connection_id, access_token_ct, key_id, created_at) VALUES (?, 'ciphertext', 'k1', ?)",
                old, now);

        // The Fyers link is not shown as the current connection and its token is never sent anywhere.
        mvc.perform(get("/api/live/status").header("Authorization", access(userId)))
                .andExpect(jsonPath("$.connection").doesNotExist());
        mvc.perform(get("/api/live/broker/funds").header("Authorization", access(userId)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NO_BROKER_CONNECTION"));

        // Starting an Angel One link closes it locally, erases its token and audits that.
        connect(stepUp(userId));
        assertThat(jdbc.queryForObject("SELECT status FROM broker_connections WHERE id = ?", String.class, old))
                .isEqualTo("DISCONNECTED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM broker_credentials WHERE connection_id = ?", Integer.class, old))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM live_audit_log WHERE user_id = ? AND action = 'RETIRE_OTHER_BROKER_LINK'",
                Integer.class, userId)).isEqualTo(1);
    }
}
