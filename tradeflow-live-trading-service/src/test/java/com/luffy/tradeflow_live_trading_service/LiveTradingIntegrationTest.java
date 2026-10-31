package com.luffy.tradeflow_live_trading_service;

import com.luffy.tradeflow.common.events.EventCodec;
import com.luffy.tradeflow.common.events.Events;
import com.luffy.tradeflow.common.events.Topics;
import com.luffy.tradeflow.common.events.TradingEnvironment;
import com.luffy.tradeflow.common.testsupport.TestJwts;
import com.luffy.tradeflow_live_trading_service.broker.BrokerAdapter;
import com.luffy.tradeflow_live_trading_service.messaging.ReadinessEventListener;
import com.luffy.tradeflow_live_trading_service.platform.PlatformChecks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import com.luffy.tradeflow.common.web.ApiException;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Live trading with a TEST-ONLY fake broker: the guard (step-up, session, MFA, readiness, kill
 * switch, fail closed), broker linking with state + PKCE, encrypted credentials, one-time
 * parameter-bound confirmations, limits, D5 (revocation blocks new orders but never cancels and
 * never prevents cancelling), notifications and the hash-chained audit log.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LiveTradingIntegrationTest {

    @TestConfiguration
    static class FakeBroker {
        @Bean
        @Primary
        FakeBrokerAdapter fakeBrokerAdapter() {
            return new FakeBrokerAdapter();
        }
    }

    private static final AtomicLong USERS = new AtomicLong(90_000);

    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired FakeBrokerAdapter broker;
    @Autowired ReadinessEventListener readinessListener;
    @Autowired EventCodec codec;
    @MockitoBean PlatformChecks platform;

    /** Per-user state the mocked platform reports. */
    final Map<Long, Boolean> eligible = new ConcurrentHashMap<>();
    final Map<Long, Integer> tokenVersion = new ConcurrentHashMap<>();
    final Map<Long, Boolean> mfa = new ConcurrentHashMap<>();

    @BeforeEach
    void platform() {
        broker.mode = FakeBrokerAdapter.Mode.ACCEPT;
        when(platform.eligibility(anyLong())).thenAnswer(inv -> {
            long id = inv.getArgument(0);
            boolean ok = eligible.getOrDefault(id, true);
            return new PlatformChecks.Eligibility(id, ok ? "QUALIFIED" : "ACCESS_REVOKED", ok,
                    ok ? List.of() : List.of("Readiness was revoked"), 1, Instant.now(), null, null, Instant.now());
        });
        when(platform.session(anyLong())).thenAnswer(inv -> {
            long id = inv.getArgument(0);
            return new PlatformChecks.SessionState(id, true, tokenVersion.getOrDefault(id, 0), mfa.getOrDefault(id, true));
        });
    }

    // ------------------------------------------------------------------ tokens

    static String stepUp(long id) {
        return stepUp(id, Instant.now(), 0, List.of("pwd", "otp"));
    }

    static String stepUp(long id, Instant authTime, int tv, List<String> amr) {
        return TestJwts.bearer(TestJwts.withAudience(id, "tradeflow-live",
                Map.of("amr", amr, "auth_time", authTime.getEpochSecond(), "tv", tv)));
    }

    /** An administrator's own step-up token (password + TOTP). */
    static String adminStepUp(long id, Instant authTime, int tv, List<String> amr) {
        return TestJwts.bearer(TestJwts.withAudience(id, "ADMIN", "tradeflow-live",
                Map.of("amr", amr, "auth_time", authTime.getEpochSecond(), "tv", tv, "sid", "sid-" + id)));
    }

    static String adminStepUp(long id) {
        return adminStepUp(id, Instant.now(), 0, List.of("pwd", "otp"));
    }

    static String access(long id) {
        return TestJwts.bearer(TestJwts.withAudience(id, "tradeflow",
                Map.of("amr", List.of("pwd", "otp"), "auth_time", Instant.now().getEpochSecond(), "tv", 0)));
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode call(MockHttpServletRequestBuilder b, String auth, int expected) throws Exception {
        if (auth != null) {
            b = b.header("Authorization", auth);
        }
        String body = mvc.perform(b).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return body.isBlank() ? null : json.readTree(body);
    }

    private JsonNode send(MockHttpServletRequestBuilder b, String auth, String body, int expected) throws Exception {
        return call(b.contentType(MediaType.APPLICATION_JSON).content(body), auth, expected);
    }

    private String code(JsonNode problem) {
        return problem.get("code").asString();
    }

    private long connectedUser() throws Exception {
        long id = USERS.incrementAndGet();
        JsonNode started = call(post("/api/live/broker/connect"), stepUp(id), 200);
        String url = started.get("authorizationUrl").asString();
        String state = url.substring(url.indexOf("state=") + 6, url.indexOf('&'));
        JsonNode conn = send(post("/api/live/broker/callback"), stepUp(id),
                "{\"state\":\"" + state + "\",\"code\":\"good-code\"}", 200);
        assertThat(conn.get("status").asString()).isEqualTo("ACTIVE");
        return id;
    }

    private static final String LIMIT_ORDER =
            "{\"exchange\":\"NSE\",\"symbol\":\"TCS\",\"side\":\"BUY\",\"type\":\"LIMIT\",\"quantity\":2,\"limitPrice\":3500.00}";

    private JsonNode placeLimit(long id) throws Exception {
        JsonNode preview = send(post("/api/live/orders/preview"), stepUp(id), LIMIT_ORDER, 200);
        return send(post("/api/live/orders"), stepUp(id), "{\"confirmationId\":\"" + preview.get("confirmationId").asString()
                + "\",\"clientOrderId\":\"" + UUID.randomUUID() + "\",\"order\":" + LIMIT_ORDER + "}", 200);
    }

    private List<String> auditActions(long id) {
        return jdbc.queryForList("select action || ':' || outcome || ':' || coalesce(detail,'') from live_audit_log "
                + "where user_id = ? order by seq", String.class, id);
    }

    // ------------------------------------------------------------------ guard

    @Test
    void everyStateChangingCallNeedsAFreshPasswordPlusTotpStepUp() throws Exception {
        long id = USERS.incrementAndGet();
        // An ordinary access token can read but not act.
        call(get("/api/live/status"), access(id), 200);
        assertThat(code(call(post("/api/live/broker/connect"), access(id), 403))).isEqualTo("STEP_UP_REQUIRED");
        // Step-up audience but without an OTP factor.
        assertThat(code(call(post("/api/live/broker/connect"), stepUp(id, Instant.now(), 0, List.of("pwd")), 403)))
                .isEqualTo("STEP_UP_REQUIRED");
        // Authenticated more than 10 minutes ago.
        assertThat(code(call(post("/api/live/broker/connect"), stepUp(id, Instant.now().minusSeconds(11 * 60), 0,
                List.of("pwd", "otp")), 403))).isEqualTo("STEP_UP_REQUIRED");
        // Sessions revoked since the token was issued ("sign out everywhere" bumps the token version).
        tokenVersion.put(id, 1);
        assertThat(code(call(post("/api/live/broker/connect"), stepUp(id), 401))).isEqualTo("SESSION_REVOKED");
        tokenVersion.put(id, 0);
        // MFA switched off.
        mfa.put(id, false);
        assertThat(code(call(post("/api/live/broker/connect"), stepUp(id), 403))).isEqualTo("MFA_REQUIRED");
        mfa.put(id, true);
        // Not qualified.
        eligible.put(id, false);
        assertThat(code(call(post("/api/live/broker/connect"), stepUp(id), 403))).isEqualTo("NOT_ELIGIBLE");
        // A service token is not a user.
        assertThat(code(call(post("/api/live/broker/connect"), TestJwts.bearer(TestJwts.service()), 403)))
                .isEqualTo("NOT_A_USER");
        // No token / untrusted signer.
        call(post("/api/live/broker/connect"), null, 401);
        call(post("/api/live/broker/connect"), TestJwts.bearer(TestJwts.untrustedUser(id)), 401);

        assertThat(auditActions(id)).containsExactly(
                "CONNECT_BROKER:REFUSED:STEP_UP_REQUIRED", "CONNECT_BROKER:REFUSED:STEP_UP_REQUIRED",
                "CONNECT_BROKER:REFUSED:STEP_UP_REQUIRED", "CONNECT_BROKER:REFUSED:SESSION_REVOKED",
                "CONNECT_BROKER:REFUSED:MFA_REQUIRED", "CONNECT_BROKER:REFUSED:NOT_ELIGIBLE");
    }

    @Test
    void failsClosedWhenASafetyCheckCannotBeMade() throws Exception {
        long id = USERS.incrementAndGet();
        when(platform.eligibility(id)).thenThrow(new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                "LIVE_CHECK_UNAVAILABLE", "down"));
        assertThat(code(call(post("/api/live/broker/connect"), stepUp(id), 503))).isEqualTo("LIVE_CHECK_UNAVAILABLE");
        // The dashboard still loads and says readiness could not be checked (not "eligible").
        JsonNode s = call(get("/api/live/status"), access(id), 200);
        assertThat(s.get("eligibility").get("available").asBoolean()).isFalse();
        assertThat(s.get("eligibility").get("eligible").asBoolean()).isFalse();
    }

    // ------------------------------------------------------------------ connection and credentials

    @Test
    void brokerLinkUsesOneTimeStateAndStoresTokensOnlyEncrypted() throws Exception {
        long id = USERS.incrementAndGet();
        JsonNode started = call(post("/api/live/broker/connect"), stepUp(id), 200);
        String url = started.get("authorizationUrl").asString();
        assertThat(url).contains("code_challenge=");
        String state = url.substring(url.indexOf("state=") + 6, url.indexOf('&'));
        // Another user cannot complete this user's link.
        assertThat(code(send(post("/api/live/broker/callback"), stepUp(id + 500_000),
                "{\"state\":\"" + state + "\",\"code\":\"good-code\"}", 400))).isEqualTo("INVALID_STATE");
        send(post("/api/live/broker/callback"), stepUp(id), "{\"state\":\"" + state + "\",\"code\":\"good-code\"}", 200);
        // The state is single use.
        assertThat(code(send(post("/api/live/broker/callback"), stepUp(id),
                "{\"state\":\"" + state + "\",\"code\":\"good-code\"}", 400))).isEqualTo("INVALID_STATE");

        // At rest: ciphertext only, no plaintext token anywhere in the row; the state is stored hashed.
        Map<String, Object> row = jdbc.queryForMap("select c.* from broker_credentials c join broker_connections b "
                + "on b.id = c.connection_id where b.user_id = ?", id);
        assertThat(row.toString()).doesNotContain(FakeBrokerAdapter.ACCESS_TOKEN_PREFIX).doesNotContain("fake-refresh-token");
        assertThat(String.valueOf(row.get("access_token_ct"))).startsWith("v1:");
        assertThat(jdbc.queryForList("select state_hash from oauth_states where user_id = ?", String.class, id))
                .doesNotContain(state);

        // No API response carries a broker token.
        String status = mvc.perform(get("/api/live/status").header("Authorization", access(id))).andReturn()
                .getResponse().getContentAsString();
        String admin = mvc.perform(get("/api/admin/live/users/" + id).header("Authorization",
                TestJwts.bearer(TestJwts.admin(1)))).andReturn().getResponse().getContentAsString();
        assertThat(status + admin).doesNotContain(FakeBrokerAdapter.ACCESS_TOKEN_PREFIX).doesNotContain("access_token_ct");
        assertThat(status).contains("\"status\":\"ACTIVE\"");

        // Disconnect erases the tokens and needs step-up.
        call(post("/api/live/broker/disconnect"), access(id), 403);
        call(post("/api/live/broker/disconnect"), stepUp(id), 200);
        assertThat(jdbc.queryForObject("select count(*) from broker_credentials c join broker_connections b "
                + "on b.id = c.connection_id where b.user_id = ?", Integer.class, id)).isZero();
    }

    // ------------------------------------------------------------------ orders

    @Test
    void ordersNeedAOneTimeConfirmationBoundToTheExactParameters() throws Exception {
        long id = connectedUser();
        JsonNode preview = send(post("/api/live/orders/preview"), stepUp(id), LIMIT_ORDER, 200);
        assertThat(preview.get("estimatedValue").decimalValue()).isEqualByComparingTo("7000.00");
        assertThat(preview.get("warning").asString()).contains("REAL order");
        String conf = preview.get("confirmationId").asString();

        String changed = LIMIT_ORDER.replace("\"quantity\":2", "\"quantity\":3");
        assertThat(code(send(post("/api/live/orders"), stepUp(id), "{\"confirmationId\":\"" + conf
                + "\",\"clientOrderId\":\"" + UUID.randomUUID() + "\",\"order\":" + changed + "}", 409)))
                .isEqualTo("CONFIRMATION_MISMATCH");
        // Another user cannot use it.
        long other = connectedUser();
        assertThat(code(send(post("/api/live/orders"), stepUp(other), "{\"confirmationId\":\"" + conf
                + "\",\"clientOrderId\":\"" + UUID.randomUUID() + "\",\"order\":" + LIMIT_ORDER + "}", 409)))
                .isEqualTo("CONFIRMATION_INVALID");
        // An ordinary token cannot place.
        send(post("/api/live/orders"), access(id), "{\"confirmationId\":\"" + conf
                + "\",\"clientOrderId\":\"" + UUID.randomUUID() + "\",\"order\":" + LIMIT_ORDER + "}", 403);

        String clientId = UUID.randomUUID().toString();
        String place = "{\"confirmationId\":\"" + conf + "\",\"clientOrderId\":\"" + clientId + "\",\"order\":" + LIMIT_ORDER + "}";
        JsonNode order = send(post("/api/live/orders"), stepUp(id), place, 200);
        assertThat(order.get("id").asString()).startsWith("L-");
        assertThat(order.get("status").asString()).isEqualTo("OPEN");
        assertThat(order.get("brokerOrderId").asString()).startsWith("B-");
        // Retrying the same request is idempotent; it does not send a second order.
        int sent = broker.placed.size();
        assertThat(send(post("/api/live/orders"), stepUp(id), place, 200).get("id").asString())
                .isEqualTo(order.get("id").asString());
        assertThat(broker.placed).hasSize(sent);
        // The confirmation is single use.
        assertThat(code(send(post("/api/live/orders"), stepUp(id), "{\"confirmationId\":\"" + conf
                + "\",\"clientOrderId\":\"" + UUID.randomUUID() + "\",\"order\":" + LIMIT_ORDER + "}", 409)))
                .isEqualTo("CONFIRMATION_EXPIRED");
    }

    @Test
    void anUnconfirmedBrokerOutcomeIsRecordedAsUnknownNotAsFailed() throws Exception {
        long id = connectedUser();
        broker.mode = FakeBrokerAdapter.Mode.TIMEOUT;
        assertThat(placeLimit(id).get("status").asString()).isEqualTo("UNKNOWN");
        long other = connectedUser();
        broker.mode = FakeBrokerAdapter.Mode.REJECT;
        JsonNode rejected = placeLimit(other);
        assertThat(rejected.get("status").asString()).isEqualTo("REJECTED");
        assertThat(rejected.get("statusMessage").asString()).contains("Insufficient funds");
        assertThat(auditActions(id)).anyMatch(a -> a.startsWith("SUBMIT_ORDER:UNKNOWN"));
        assertThat(auditActions(other)).anyMatch(a -> a.startsWith("SUBMIT_ORDER:REJECTED"));
        // A definite rejection does not block the next order.
        broker.mode = FakeBrokerAdapter.Mode.ACCEPT;
        assertThat(placeLimit(other).get("status").asString()).isEqualTo("OPEN");
    }

    // ------------------------------------------------------------------ reconciliation

    private String placeBody(JsonNode preview, String clientId) {
        return "{\"confirmationId\":\"" + preview.get("confirmationId").asString() + "\",\"clientOrderId\":\""
                + clientId + "\",\"order\":" + LIMIT_ORDER + "}";
    }

    private List<String> reconcileDecisions(long id) throws Exception {
        JsonNode r = call(post("/api/live/reconciliation"), access(id), 200);
        List<String> out = new java.util.ArrayList<>();
        r.forEach(x -> out.add(x.get("decision").asString()));
        return out;
    }

    private void ageOrder(String orderId, long minutes) {
        jdbc.update("update live_orders set created_at = ? where id = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(minutes * 60)), orderId);
    }

    @Test
    void anOrderLostInATimeoutIsFoundAtTheBrokerNeverResubmittedAndBlocksNewOrdersUntilThen() throws Exception {
        long id = connectedUser();
        broker.mode = FakeBrokerAdapter.Mode.TIMEOUT_AFTER_PLACING;
        String clientId = UUID.randomUUID().toString();
        JsonNode preview = send(post("/api/live/orders/preview"), stepUp(id), LIMIT_ORDER, 200);
        JsonNode unknown = send(post("/api/live/orders"), stepUp(id), placeBody(preview, clientId), 200);
        assertThat(unknown.get("status").asString()).isEqualTo("UNKNOWN");
        int sent = broker.placed.size();
        broker.mode = FakeBrokerAdapter.Mode.ACCEPT;

        // The client retrying the same request gets the same UNKNOWN order: nothing is resent.
        assertThat(send(post("/api/live/orders"), stepUp(id), placeBody(preview, clientId), 200).get("id").asString())
                .isEqualTo(unknown.get("id").asString());
        // A NEW order (new preview, new client id) is refused while the first one's outcome is open,
        // so the user cannot accidentally buy twice.
        assertThat(code(send(post("/api/live/orders/preview"), stepUp(id), LIMIT_ORDER, 409))).isEqualTo("UNRESOLVED_ORDER");
        assertThat(broker.placed).hasSize(sent);

        // Reconciliation finds it in the broker's order book by the client order id it was sent with.
        assertThat(reconcileDecisions(id)).containsExactly("LINKED");
        JsonNode linked = call(get("/api/live/orders/" + unknown.get("id").asString()), access(id), 200);
        assertThat(linked.get("status").asString()).isEqualTo("OPEN");
        assertThat(linked.get("brokerOrderId").asString()).startsWith("B-");
        assertThat(broker.placed).hasSize(sent); // still exactly one real order
        // Running it again changes nothing (idempotent) and writes no second decision.
        assertThat(reconcileDecisions(id)).containsExactly("UNCHANGED");
        assertThat(auditActions(id).stream().filter(a -> a.startsWith("RECONCILE_ORDER:INFO:" + unknown.get("id").asString()
                + " LINKED"))).hasSize(1);
        assertThat(call(get("/api/live/notifications"), access(id), 200).get(0).get("kind").asString())
                .isEqualTo("ORDER_CONFIRMED");
        // Now the user may trade again.
        assertThat(placeLimit(id).get("status").asString()).isEqualTo("OPEN");
    }

    @Test
    void anOrderMissingFromTheBrokerStaysUnknownThroughTheAbsenceWindowThenNeedsTheUsersReview() throws Exception {
        long id = connectedUser();
        broker.mode = FakeBrokerAdapter.Mode.TIMEOUT; // never reached the broker
        String orderId = placeLimit(id).get("id").asString();
        broker.mode = FakeBrokerAdapter.Mode.ACCEPT;
        int sent = broker.placed.size();

        // Inside the window: the broker may still list it late. Keep looking; do not conclude anything.
        assertThat(reconcileDecisions(id)).containsExactly("NOT_FOUND_YET");
        // Broker unreachable: nothing changes.
        broker.bookUnavailable = true;
        try {
            assertThat(reconcileDecisions(id)).containsExactly("BROKER_UNAVAILABLE");
        } finally {
            broker.bookUnavailable = false;
        }
        assertThat(call(get("/api/live/orders/" + orderId), access(id), 200).get("status").asString()).isEqualTo("UNKNOWN");

        // Past the window: the user is asked to check their broker account. Still never resubmitted.
        ageOrder(orderId, 20);
        assertThat(reconcileDecisions(id)).containsExactly("NEEDS_REVIEW");
        JsonNode review = call(get("/api/live/orders/" + orderId), access(id), 200);
        assertThat(review.get("status").asString()).isEqualTo("NEEDS_REVIEW");
        assertThat(review.get("reviewReason").asString()).isEqualTo("NOT_FOUND_AT_BROKER");
        assertThat(call(get("/api/live/notifications"), access(id), 200).get(0).get("message").asString())
                .contains("NOT resubmitted");
        assertThat(code(send(post("/api/live/orders/preview"), stepUp(id), LIMIT_ORDER, 409))).isEqualTo("UNRESOLVED_ORDER");

        // Resolving needs step-up (an access token is refused) and only applies to orders under review.
        call(post("/api/live/orders/" + orderId + "/resolve"), access(id), 403);
        JsonNode resolved = call(post("/api/live/orders/" + orderId + "/resolve"), stepUp(id), 200);
        assertThat(resolved.get("status").asString()).isEqualTo("RESOLVED_BY_USER");
        assertThat(code(call(post("/api/live/orders/" + orderId + "/resolve"), stepUp(id), 409))).isEqualTo("NOT_UNDER_REVIEW");
        assertThat(broker.placed).hasSize(sent);
        assertThat(auditActions(id)).anyMatch(a -> a.startsWith("RECONCILE_ORDER:INFO:" + orderId + " NEEDS_REVIEW"))
                .anyMatch(a -> a.startsWith("RESOLVE_ORDER:ALLOWED:" + orderId + " NOT_FOUND_AT_BROKER [step-up pwd+otp"))
                .anyMatch(a -> a.startsWith("RECONCILE_ORDER:INFO:" + orderId + " BROKER_UNAVAILABLE"));
        // The user can trade again.
        assertThat(placeLimit(id).get("status").asString()).isEqualTo("OPEN");
    }

    @Test
    void twoBrokerOrdersForOneRequestAreFlaggedAndNeitherIsCancelled() throws Exception {
        long id = connectedUser();
        broker.mode = FakeBrokerAdapter.Mode.TIMEOUT_AFTER_PLACING;
        JsonNode order = placeLimit(id);
        broker.mode = FakeBrokerAdapter.Mode.ACCEPT;
        // The broker somehow holds two orders with this client order id.
        BrokerAdapter.BrokerOrder first = broker.book.stream()
                .filter(b -> b.clientOrderId() != null && broker.placed.stream().anyMatch(p -> p.clientOrderId().equals(b.clientOrderId())))
                .reduce((a, b) -> b).orElseThrow();
        broker.book.add(new BrokerAdapter.BrokerOrder("B-dup-" + id, first.clientOrderId(), first.exchange(), first.symbol(),
                first.side(), first.type(), first.quantity(), 0, first.price(), null, "OPEN", "open", Instant.now()));
        int cancels = broker.cancelled.size();
        assertThat(reconcileDecisions(id)).containsExactly("NEEDS_REVIEW");
        JsonNode review = call(get("/api/live/orders/" + order.get("id").asString()), access(id), 200);
        assertThat(review.get("reviewReason").asString()).isEqualTo("DUPLICATE_AT_BROKER");
        assertThat(review.get("brokerOrderId").isNull()).isTrue(); // not silently linked to either
        assertThat(broker.cancelled).hasSize(cancels);
    }

    @Test
    void brokerStatusRefreshIsMonotonicAndIgnoresStaleOrDuplicateSnapshots() throws Exception {
        long id = connectedUser();
        JsonNode order = placeLimit(id);
        String brokerId = order.get("brokerOrderId").asString();
        BrokerAdapter.OrderRequest req = broker.placed.get(broker.placed.size() - 1);
        Instant t0 = Instant.now();
        broker.book.removeIf(b -> brokerId.equals(b.brokerOrderId()));
        broker.book.add(broker.bookEntry(brokerId, req, "PARTIALLY_FILLED", 1, t0.plusSeconds(10)));
        assertThat(reconcileDecisions(id)).containsExactly("UPDATED");
        JsonNode partial = call(get("/api/live/orders/" + order.get("id").asString()), access(id), 200);
        assertThat(partial.get("status").asString()).isEqualTo("PARTIALLY_FILLED");
        assertThat(partial.get("filledQuantity").asLong()).isEqualTo(1);

        // A delayed, older snapshot (still OPEN, nothing filled) must not move it backwards.
        broker.book.removeIf(b -> brokerId.equals(b.brokerOrderId()));
        broker.book.add(broker.bookEntry(brokerId, req, "OPEN", 0, t0.plusSeconds(5)));
        assertThat(reconcileDecisions(id)).containsExactly("UNCHANGED");
        // Filled.
        broker.book.removeIf(b -> brokerId.equals(b.brokerOrderId()));
        broker.book.add(broker.bookEntry(brokerId, req, "FILLED", 2, t0.plusSeconds(20)));
        assertThat(reconcileDecisions(id)).containsExactly("UPDATED");
        // Terminal: no longer reconciled at all, even if a later snapshot disagrees.
        broker.book.removeIf(b -> brokerId.equals(b.brokerOrderId()));
        broker.book.add(broker.bookEntry(brokerId, req, "OPEN", 0, t0.plusSeconds(30)));
        assertThat(reconcileDecisions(id)).isEmpty();
        JsonNode filled = call(get("/api/live/orders/" + order.get("id").asString()), access(id), 200);
        assertThat(filled.get("status").asString()).isEqualTo("FILLED");
        assertThat(filled.get("filledQuantity").asLong()).isEqualTo(2);
    }

    @Test
    void anOrderStuckInSubmittingAfterACrashIsPickedUpButNotWhileItsOwnCallMayStillAnswer() throws Exception {
        long id = connectedUser();
        broker.mode = FakeBrokerAdapter.Mode.TIMEOUT_AFTER_PLACING;
        String orderId = placeLimit(id).get("id").asString();
        broker.mode = FakeBrokerAdapter.Mode.ACCEPT;
        // Simulate a crash between saving the order and recording the broker's answer.
        jdbc.update("update live_orders set status = 'SUBMITTING' where id = ?", orderId);
        assertThat(reconcileDecisions(id)).containsExactly("IN_FLIGHT");
        ageOrder(orderId, 3); // past the in-flight grace period
        assertThat(reconcileDecisions(id)).containsExactly("LINKED");
        assertThat(call(get("/api/live/orders/" + orderId), access(id), 200).get("status").asString()).isEqualTo("OPEN");
    }

    @Test
    void reconciliationNeverUsesADifferentBrokerAccountAndNotificationsAreDeliveredOnce() throws Exception {
        long id = connectedUser();
        broker.mode = FakeBrokerAdapter.Mode.TIMEOUT;
        String orderId = placeLimit(id).get("id").asString();
        broker.mode = FakeBrokerAdapter.Mode.ACCEPT;
        // The user disconnects: no usable connection to that account, so nothing is decided.
        call(post("/api/live/broker/disconnect"), stepUp(id), 200);
        assertThat(reconcileDecisions(id)).containsExactly("NO_CONNECTION");
        assertThat(call(get("/api/live/orders/" + orderId), access(id), 200).get("status").asString()).isEqualTo("UNKNOWN");

        // A readiness event delivered twice (Kafka at-least-once) produces one notification.
        EventCodec platformCodec = new EventCodec(json, jakarta.validation.Validation.buildDefaultValidatorFactory()
                .getValidator(), Clock.systemUTC(), "tradeflow-learning-service", TradingEnvironment.PLATFORM, null);
        String message = platformCodec.encode(platformCodec.wrap(Topics.READINESS_CHANGED, String.valueOf(id),
                new Events.ReadinessChanged(id, "QUALIFIED", "EXPIRED", "EXPIRED", false, 1, Instant.now())));
        readinessListener.onReadinessChanged(message);
        readinessListener.onReadinessChanged(message);
        JsonNode notes = call(get("/api/live/notifications"), access(id), 200);
        List<String> kinds = new java.util.ArrayList<>();
        notes.forEach(n -> kinds.add(n.get("kind").asString()));
        assertThat(kinds.stream().filter("READINESS_LOST"::equals)).hasSize(1);
        // Read state is persisted and per user.
        long noteId = notes.get(0).get("id").asLong();
        call(post("/api/live/notifications/" + noteId + "/read"), access(USERS.incrementAndGet()), 404);
        call(post("/api/live/notifications/" + noteId + "/read"), access(id), 200);
        assertThat(call(get("/api/live/notifications"), access(id), 200).get(0).get("readAt").isNull()).isFalse();
    }

    @Test
    void limitsAreEnforcedAndCanOnlyBeMadeStricter() throws Exception {
        long id = connectedUser();
        String big = LIMIT_ORDER.replace("\"quantity\":2", "\"quantity\":8"); // 28,000 > 25,000 platform cap
        assertThat(code(send(post("/api/live/orders/preview"), stepUp(id), big, 422))).isEqualTo("MAX_ORDER_VALUE");

        // Tightening needs no step-up; above the platform ceiling is refused.
        send(put("/api/live/limits"), access(id),
                "{\"maxOrderValue\":30000,\"maxOrdersPerDay\":10,\"maxDailyTurnover\":100000}", 422);
        send(put("/api/live/limits"), access(id),
                "{\"maxOrderValue\":10000,\"maxOrdersPerDay\":1,\"maxDailyTurnover\":10000}", 200);
        placeLimit(id);
        assertThat(code(send(post("/api/live/orders/preview"), stepUp(id), LIMIT_ORDER, 422)))
                .isEqualTo("MAX_ORDERS_PER_DAY");
        // Loosening again is risk-increasing: step-up required.
        assertThat(code(send(put("/api/live/limits"), access(id),
                "{\"maxOrderValue\":10000,\"maxOrdersPerDay\":5,\"maxDailyTurnover\":10000}", 403)))
                .isEqualTo("STEP_UP_REQUIRED");
        send(put("/api/live/limits"), stepUp(id),
                "{\"maxOrderValue\":10000,\"maxOrdersPerDay\":5,\"maxDailyTurnover\":10000}", 200);
    }

    // ------------------------------------------------------------------ D5 and kill switch

    @Test
    void revocationBlocksNewOrdersNotifiesAndNeverCancelsOrPreventsCancelling() throws Exception {
        long id = connectedUser();
        JsonNode open = placeLimit(id);
        int cancelsBefore = broker.cancelled.size();

        // learning-service revokes readiness: the event arrives as Kafka would deliver it.
        eligible.put(id, false);
        EventCodec platformCodec = new EventCodec(json, jakarta.validation.Validation.buildDefaultValidatorFactory()
                .getValidator(), Clock.systemUTC(), "tradeflow-learning-service", TradingEnvironment.PLATFORM, null);
        readinessListener.onReadinessChanged(platformCodec.encode(platformCodec.wrap(Topics.READINESS_CHANGED,
                String.valueOf(id), new Events.ReadinessChanged(id, "QUALIFIED", "ACCESS_REVOKED", "ADMIN_REVOKED",
                        false, 1, Instant.now()))));

        JsonNode notes = call(get("/api/live/notifications"), access(id), 200);
        assertThat(notes.get(0).get("kind").asString()).isEqualTo("READINESS_LOST");
        assertThat(notes.get(0).get("message").asString()).contains("NOT cancelled");
        // Nothing was cancelled automatically.
        assertThat(broker.cancelled).hasSize(cancelsBefore);
        // New orders are blocked …
        assertThat(code(send(post("/api/live/orders/preview"), stepUp(id), LIMIT_ORDER, 403))).isEqualTo("NOT_ELIGIBLE");
        // … but the user can still see and cancel what is open.
        call(get("/api/live/orders"), access(id), 200);
        call(get("/api/live/broker/orders"), access(id), 200);
        JsonNode cancelled = call(post("/api/live/orders/" + open.get("id").asString() + "/cancel"), stepUp(id), 200);
        assertThat(cancelled.get("status").asString()).isEqualTo("CANCEL_REQUESTED");
        assertThat(broker.cancelled).hasSize(cancelsBefore + 1);
    }

    @Test
    void killSwitchesHaltNewOrdersButNotCancellationAndAreAudited() throws Exception {
        long id = connectedUser();
        JsonNode open = placeLimit(id);
        String admin = adminStepUp(7);
        // Users cannot operate kill switches; a reason is required.
        send(put("/api/admin/live/users/" + id + "/kill-switch"), stepUp(id), "{\"enabled\":true,\"reason\":\"self halt please\"}", 403);
        send(put("/api/admin/live/users/" + id + "/kill-switch"), admin, "{\"enabled\":true,\"reason\":\"short\"}", 400);

        send(put("/api/admin/live/users/" + id + "/kill-switch"), admin,
                "{\"enabled\":true,\"reason\":\"Suspicious activity under review\"}", 200);
        assertThat(code(send(post("/api/live/orders/preview"), stepUp(id), LIMIT_ORDER, 423))).isEqualTo("LIVE_TRADING_HALTED");
        call(post("/api/live/orders/" + open.get("id").asString() + "/cancel"), stepUp(id), 200);
        assertThat(call(get("/api/live/notifications"), access(id), 200).get(0).get("kind").asString())
                .isEqualTo("TRADING_HALTED");
        send(put("/api/admin/live/users/" + id + "/kill-switch"), admin, "{\"enabled\":false,\"reason\":\"Review complete, cleared\"}", 200);

        // Global switch.
        send(put("/api/admin/live/kill-switch"), admin, "{\"enabled\":true,\"reason\":\"Broker outage drill\"}", 200);
        try {
            assertThat(call(get("/api/live/status"), access(id), 200).get("halted").asBoolean()).isTrue();
            assertThat(code(send(post("/api/live/orders/preview"), stepUp(id), LIMIT_ORDER, 423))).isEqualTo("LIVE_TRADING_HALTED");
        } finally {
            send(put("/api/admin/live/kill-switch"), admin, "{\"enabled\":false,\"reason\":\"Broker outage drill over\"}", 200);
        }
        assertThat(auditActions(id)).anyMatch(a -> a.startsWith("KILL_SWITCH_ON:ALLOWED"))
                .anyMatch(a -> a.startsWith("PREVIEW_ORDER:REFUSED:LIVE_TRADING_HALTED"))
                .anyMatch(a -> a.startsWith("CANCEL_ORDER:ALLOWED"));
    }

    @Test
    void killSwitchChangesNeedTheAdministratorsOwnFreshStepUpAndEveryAttemptIsAudited() throws Exception {
        long target = USERS.incrementAndGet();
        long adminId = USERS.incrementAndGet();
        String path = "/api/admin/live/users/" + target + "/kill-switch";
        String halt = "{\"enabled\":true,\"reason\":\"Unusual order pattern reported\"}";

        // The ordinary admin access token (password only, no fresh re-authentication) is not enough.
        assertThat(code(send(put(path), TestJwts.bearer(TestJwts.admin(adminId)), halt, 403))).isEqualTo("STEP_UP_REQUIRED");
        // Step-up without the authenticator factor, or older than the maximum age.
        assertThat(code(send(put(path), adminStepUp(adminId, Instant.now(), 0, List.of("pwd")), halt, 403)))
                .isEqualTo("STEP_UP_REQUIRED");
        assertThat(code(send(put(path), adminStepUp(adminId, Instant.now().minusSeconds(11 * 60), 0,
                List.of("pwd", "otp")), halt, 403))).isEqualTo("STEP_UP_REQUIRED");
        // The administrator's own session was revoked, or their MFA switched off.
        tokenVersion.put(adminId, 3);
        assertThat(code(send(put(path), adminStepUp(adminId), halt, 401))).isEqualTo("SESSION_REVOKED");
        tokenVersion.put(adminId, 0);
        mfa.put(adminId, false);
        assertThat(code(send(put(path), adminStepUp(adminId), halt, 403))).isEqualTo("MFA_REQUIRED");
        mfa.put(adminId, true);
        // A trader's step-up token is not an administrator's.
        send(put(path), stepUp(adminId), halt, 403);
        // Nothing changed so far.
        assertThat(call(get("/api/admin/live/users/" + target), TestJwts.bearer(TestJwts.admin(adminId)), 200)
                .get("killSwitch").get("enabled").asBoolean()).isFalse();

        JsonNode on = send(put(path), adminStepUp(adminId), halt, 200);
        assertThat(on.get("enabled").asBoolean()).isTrue();
        assertThat(on.get("actorId").asLong()).isEqualTo(adminId);

        // Every attempt is on the target's chain with the administrator as actor; the change records
        // the reason, the previous state and how the administrator authenticated.
        List<String> actions = auditActions(target);
        assertThat(actions).containsExactly(
                "CHANGE_KILL_SWITCH:REFUSED:STEP_UP_REQUIRED (admin " + adminId + ")",
                "CHANGE_KILL_SWITCH:REFUSED:STEP_UP_REQUIRED (admin " + adminId + ")",
                "CHANGE_KILL_SWITCH:REFUSED:STEP_UP_REQUIRED (admin " + adminId + ")",
                "CHANGE_KILL_SWITCH:REFUSED:SESSION_REVOKED (admin " + adminId + ")",
                "CHANGE_KILL_SWITCH:REFUSED:MFA_REQUIRED (admin " + adminId + ")",
                actions.get(5));
        assertThat(actions.get(5)).startsWith("KILL_SWITCH_ON:ALLOWED:USER: Unusual order pattern reported [was off; admin "
                + adminId + "; step-up pwd+otp at ").endsWith(", session sid-" + adminId + "]");
        assertThat(jdbc.queryForList("select distinct actor_id from live_audit_log where user_id = ?", Long.class, target))
                .containsExactly(adminId);
        assertThat(call(get("/api/admin/live/users/" + target), TestJwts.bearer(TestJwts.admin(adminId)), 200)
                .get("chain").get("intact").asBoolean()).isTrue();

        // Platform-wide switch: refusals land on the platform chain (user 0).
        assertThat(code(send(put("/api/admin/live/kill-switch"), TestJwts.bearer(TestJwts.admin(adminId)), halt, 403)))
                .isEqualTo("STEP_UP_REQUIRED");
        assertThat(auditActions(0)).contains("CHANGE_KILL_SWITCH:REFUSED:STEP_UP_REQUIRED (admin " + adminId + ")");
        assertThat(call(get("/api/admin/live/kill-switch"), TestJwts.bearer(TestJwts.admin(adminId)), 200)
                .get("enabled").asBoolean()).isFalse();
    }

    // ------------------------------------------------------------------ audit and environment

    @Test
    void auditTrailIsHashChainedAndTamperingIsDetected() throws Exception {
        long id = connectedUser();
        placeLimit(id);
        String admin = TestJwts.bearer(TestJwts.admin(1));
        JsonNode view = call(get("/api/admin/live/users/" + id), admin, 200);
        assertThat(view.get("chain").get("intact").asBoolean()).isTrue();
        assertThat(view.get("chain").get("events").asInt()).isGreaterThanOrEqualTo(5);
        // Users cannot read the admin view.
        call(get("/api/admin/live/users/" + id), access(id), 403);

        jdbc.update("update live_audit_log set detail = 'forged' where user_id = ? and seq = 2", id);
        JsonNode after = call(get("/api/admin/live/users/" + id), admin, 200);
        assertThat(after.get("chain").get("intact").asBoolean()).isFalse();
        assertThat(after.get("chain").get("firstBrokenSeq").asInt()).isEqualTo(2);
    }

    @Test
    void demoEventsAreRefused() {
        EventCodec demo = new EventCodec(json, jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator(),
                Clock.systemUTC(), "tradeflow-order-service", TradingEnvironment.DEMO, null);
        String event = demo.encode(demo.wrap(Topics.READINESS_CHANGED, "1",
                new Events.ReadinessChanged(1L, "QUALIFIED", "ACCESS_REVOKED", "X", false, 1, Instant.now())));
        assertThat(codec.environment()).isEqualTo(TradingEnvironment.LIVE);
        assertThatThrownBy(() -> readinessListener.onReadinessChanged(event)).hasMessageContaining("Refusing DEMO event");
    }

    @Test
    void theFakeBrokerReceivesTheDecryptedTokenOnlyAtCallTime() throws Exception {
        long id = connectedUser();
        call(get("/api/live/broker/funds"), access(id), 200);
        assertThat(broker.tokensSeen).isNotEmpty().allMatch(t -> t.startsWith(FakeBrokerAdapter.ACCESS_TOKEN_PREFIX));
        assertThat(BrokerAdapter.BrokerSession.class.getSimpleName()).isNotNull();
        assertThat(new BrokerAdapter.BrokerSession("secret-a", "secret-r", Instant.now(), "A").toString())
                .doesNotContain("secret-a").doesNotContain("secret-r");
    }
}
