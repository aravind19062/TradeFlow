package com.luffy.tradeflow_learning_service.readiness;

import com.luffy.tradeflow.common.events.EventCodec;
import com.luffy.tradeflow.common.events.Events;
import com.luffy.tradeflow.common.events.Topics;
import com.luffy.tradeflow.common.events.TradingEnvironment;
import com.luffy.tradeflow.common.kafka.InconsistentStateException;
import com.luffy.tradeflow.common.outbox.OutboxEventRepository;
import com.luffy.tradeflow.common.testsupport.TestJwts;
import com.luffy.tradeflow_learning_service.academy.Catalog;
import com.luffy.tradeflow_learning_service.academy.ContentCatalog;
import com.luffy.tradeflow_learning_service.messaging.DemoOrderEventListener;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Readiness end to end through the HTTP API and the Kafka listener (events delivered as Kafka would):
 * the state machine, server-verified exercises, the final assessment's cooldown and attempt limit,
 * the authorised reviewer, the acknowledgement, fail-closed eligibility, revocation, expiry, policy
 * changes and the tamper-evident audit chain.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReadinessIntegrationTest {

    private static final AtomicLong USERS = new AtomicLong(70_000);

    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired ContentCatalog catalog;
    @Autowired EventCodec codec;
    @Autowired DemoOrderEventListener listener;
    @Autowired JdbcTemplate jdbc;
    @Autowired OutboxEventRepository outbox;
    @Autowired ReadinessService readiness;

    // ------------------------------------------------------------------ helpers

    private static String user(long id) {
        return TestJwts.bearer(TestJwts.user(id));
    }

    private JsonNode call(MockHttpServletRequestBuilder b, String auth, int expected) throws Exception {
        if (auth != null) {
            b = b.header("Authorization", auth);
        }
        String body = mvc.perform(b).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return body.isBlank() ? null : json.readTree(body);
    }

    private JsonNode postJson(String url, String auth, String body, int expected) throws Exception {
        return call(post(url).contentType(MediaType.APPLICATION_JSON).content(body), auth, expected);
    }

    private String state(long id) throws Exception {
        return call(get("/api/readiness"), user(id), 200).get("state").asString();
    }

    private String answers(JsonNode attempt, List<Catalog.Question> bank, int wrong) {
        List<String> parts = new ArrayList<>();
        int i = 0;
        for (JsonNode q : attempt.get("questions")) {
            Catalog.Question question = bank.stream().filter(x -> x.key().equals(q.get("key").asString())).findFirst().orElseThrow();
            List<String> keys = i++ < wrong
                    ? List.of(question.options().stream().filter(o -> !o.correct()).findFirst().orElseThrow().key())
                    : new ArrayList<>(question.correctKeys());
            parts.add("{\"questionKey\":\"" + question.key() + "\",\"optionKeys\":[\"" + String.join("\",\"", keys) + "\"]}");
        }
        return "{\"answers\":[" + String.join(",", parts) + "]}";
    }

    private void completeAllModules(long id) throws Exception {
        for (Catalog.Module m : catalog.content().modules()) {
            for (Catalog.Lesson l : m.lessons()) {
                call(post("/api/academy/modules/" + m.slug() + "/lessons/" + l.slug() + "/complete"), user(id), 204);
            }
            JsonNode attempt = call(post("/api/academy/modules/" + m.slug() + "/quiz/attempts"), user(id), 200);
            postJson("/api/academy/attempts/" + attempt.get("id").asString() + "/submit", user(id),
                    answers(attempt, m.questions(), 0), 200);
        }
    }

    private void deliver(String topic, long userId, Object payload) {
        String message = codec.encode(codec.wrap(topic, String.valueOf(userId), payload));
        switch (topic) {
            case Topics.ORDERS_PLACED -> listener.onPlaced(message);
            case Topics.ORDERS_RESERVED -> listener.onReserved(message);
            case Topics.ORDERS_CANCELLED -> listener.onCancelled(message);
            case Topics.TRADES_EXECUTED -> listener.onExecuted(message);
            default -> throw new IllegalArgumentException(topic);
        }
    }

    private UUID placeDemoOrder(long id, String symbol, Events.OrderSide side, Events.OrderType type, long qty) {
        UUID orderId = UUID.randomUUID();
        deliver(Topics.ORDERS_PLACED, id, new Events.OrderPlaced(orderId, id, symbol, side, type, qty, new BigDecimal("100.00")));
        return orderId;
    }

    /** Starts every exercise and produces exactly the DEMO events each one requires. */
    private void completeAllExercises(long id) throws Exception {
        for (String slug : List.of("market-order", "limit-order-cancel", "stop-loss")) {
            call(post("/api/readiness/exercises/" + slug + "/start"), user(id), 204);
        }
        // Reserved arrives before placed (different topics are not ordered): still counts.
        UUID buy = UUID.randomUUID();
        deliver(Topics.ORDERS_RESERVED, id, new Events.OrderReserved(buy, id, new BigDecimal("1000.00"), 0));
        deliver(Topics.ORDERS_PLACED, id, new Events.OrderPlaced(buy, id, "TCS", Events.OrderSide.BUY,
                Events.OrderType.MARKET, 10, new BigDecimal("100.00")));
        deliver(Topics.TRADES_EXECUTED, id, new Events.TradeExecuted(UUID.randomUUID(), buy, id, "TCS", Events.OrderSide.BUY,
                10, new BigDecimal("100.00"), new BigDecimal("1000.00"), Instant.now()));
        UUID limit = placeDemoOrder(id, "INFY", Events.OrderSide.BUY, Events.OrderType.LIMIT, 1);
        deliver(Topics.ORDERS_CANCELLED, id, new Events.OrderCancelled(limit, id, "Cancelled by user"));
        UUID stop = placeDemoOrder(id, "TCS", Events.OrderSide.SELL, Events.OrderType.STOP, 10);
        deliver(Topics.ORDERS_RESERVED, id, new Events.OrderReserved(stop, id, BigDecimal.ZERO, 10));

        JsonNode scenario = call(post("/api/readiness/exercises/position-sizing/scenario"), user(id), 200);
        BigDecimal account = scenario.get("accountValue").decimalValue();
        BigDecimal risk = scenario.get("riskPct").decimalValue();
        BigDecimal perShare = scenario.get("entryPrice").decimalValue().subtract(scenario.get("stopPrice").decimalValue());
        long qty = account.multiply(risk).divide(BigDecimal.valueOf(100)).divide(perShare, 0, java.math.RoundingMode.FLOOR).longValue();
        assertThat(postJson("/api/readiness/exercises/position-sizing/answer", user(id), "{\"quantity\":" + (qty + 1) + "}", 200)
                .get("correct").asBoolean()).isFalse();
        assertThat(postJson("/api/readiness/exercises/position-sizing/answer", user(id), "{\"quantity\":" + qty + "}", 200)
                .get("correct").asBoolean()).isTrue();
        UUID sized = placeDemoOrder(id, scenario.get("symbol").asString(), Events.OrderSide.BUY, Events.OrderType.MARKET, qty);
        deliver(Topics.ORDERS_RESERVED, id, new Events.OrderReserved(sized, id, new BigDecimal("5000.00"), 0));
    }

    private JsonNode takeFinal(long id, int wrong) throws Exception {
        JsonNode attempt = call(post("/api/readiness/assessment/attempts"), user(id), 200);
        return postJson("/api/academy/attempts/" + attempt.get("id").asString() + "/submit", user(id),
                answers(attempt, catalog.content().finalQuestions(), wrong), 200);
    }

    /** Moves the user's final-assessment attempts back in time (simulates the 24 h cooldown passing). */
    private void ageFinalAttempts(long id) {
        jdbc.update("update quiz_attempts set started_at = started_at - interval '2' day, submitted_at = submitted_at - interval '2' day,"
                + " expires_at = expires_at - interval '2' day where user_id = ? and quiz_key = 'final'", id);
        jdbc.update("update readiness_status set cycle_started_at = cycle_started_at - interval '2' day where user_id = ?", id);
    }

    private long reachAssessment() throws Exception {
        long id = USERS.incrementAndGet();
        assertThat(state(id)).isEqualTo("NOT_STARTED");
        completeAllModules(id);
        assertThat(state(id)).isEqualTo("IN_PROGRESS");
        completeAllExercises(id);
        assertThat(state(id)).isEqualTo("ASSESSMENT_PENDING");
        return id;
    }

    private void qualify(long id) throws Exception {
        assertThat(takeFinal(id, 0).get("passed").asBoolean()).isTrue();
        assertThat(state(id)).isEqualTo("ACKNOWLEDGEMENT_PENDING");
        JsonNode text = call(get("/api/readiness/acknowledgement"), user(id), 200);
        postJson("/api/readiness/acknowledgement", user(id), "{\"textVersion\":" + text.get("textVersion").asInt()
                + ",\"typedPhrase\":\"" + text.get("phrase").asString() + "\"}", 200);
        assertThat(state(id)).isEqualTo("QUALIFIED");
    }

    private JsonNode eligibility(long id) throws Exception {
        return call(get("/internal/readiness/" + id + "/eligibility"), TestJwts.bearer(TestJwts.service()), 200);
    }

    // ------------------------------------------------------------------ tests

    @Test
    void fullJourneyToQualifiedAndFailClosedEligibility() throws Exception {
        long id = reachAssessment();
        JsonNode status = call(get("/api/readiness"), user(id), 200);
        assertThat(status.at("/checklist/exercisesDone").asBoolean()).isTrue();
        assertThat(status.get("eligibleForLiveTrading").asBoolean()).isFalse();
        assertThat(eligibility(id).get("eligible").asBoolean()).isFalse();

        // The acknowledgement is refused before the assessment is passed.
        postJson("/api/readiness/acknowledgement", user(id), "{\"textVersion\":1,\"typedPhrase\":\"I UNDERSTAND THE RISKS\"}", 409);
        assertThat(takeFinal(id, 0).get("passed").asBoolean()).isTrue();
        postJson("/api/readiness/acknowledgement", user(id), "{\"textVersion\":1,\"typedPhrase\":\"i understand\"}", 422);
        postJson("/api/readiness/acknowledgement", user(id), "{\"textVersion\":1,\"typedPhrase\":\"I UNDERSTAND THE RISKS\"}", 200);
        JsonNode qualified = call(get("/api/readiness"), user(id), 200);
        assertThat(qualified.get("state").asString()).isEqualTo("QUALIFIED");
        assertThat(qualified.get("expiresAt").asString()).isNotBlank();

        JsonNode e = eligibility(id);
        assertThat(e.get("eligible").asBoolean()).isTrue();
        assertThat(e.get("qualifiedPolicyVersion").asInt()).isEqualTo(1);
        // Only services may ask; users (and the gateway) cannot.
        call(get("/internal/readiness/" + id + "/eligibility"), user(id), 403);
        call(get("/internal/readiness/" + id + "/eligibility"), null, 401);

        // Every transition is published for live-trading-service and recorded in an intact chain.
        assertThat(outbox.findAll().stream().filter(o -> o.getTopic().equals(Topics.READINESS_CHANGED)
                && o.getMessageKey().equals(String.valueOf(id)))).hasSize(4); // CREATED is audit-only
        JsonNode admin = call(get("/api/admin/readiness/users/" + id), TestJwts.bearer(TestJwts.admin(1)), 200);
        assertThat(admin.at("/chain/intact").asBoolean()).isTrue();
        assertThat(admin.get("history")).extracting(h -> h.get("toState").asString()).containsExactly(
                "NOT_STARTED", "IN_PROGRESS", "ASSESSMENT_PENDING", "ACKNOWLEDGEMENT_PENDING", "QUALIFIED");
    }

    @Test
    void exercisesNeedTheRightActionAfterTheyStart() throws Exception {
        long id = USERS.incrementAndGet();
        // A market buy BEFORE starting the exercise does not count.
        UUID early = placeDemoOrder(id, "TCS", Events.OrderSide.BUY, Events.OrderType.MARKET, 1);
        deliver(Topics.TRADES_EXECUTED, id, new Events.TradeExecuted(UUID.randomUUID(), early, id, "TCS",
                Events.OrderSide.BUY, 1, BigDecimal.TEN, BigDecimal.TEN, Instant.now()));
        call(post("/api/readiness/exercises/market-order/start"), user(id), 204);
        // A cancelled LIMIT that expired (not cancelled by the user) does not count; nor does a SELL market.
        call(post("/api/readiness/exercises/limit-order-cancel/start"), user(id), 204);
        UUID expired = placeDemoOrder(id, "INFY", Events.OrderSide.BUY, Events.OrderType.LIMIT, 1);
        deliver(Topics.ORDERS_CANCELLED, id, new Events.OrderCancelled(expired, id, "limit order expired"));
        UUID sell = placeDemoOrder(id, "TCS", Events.OrderSide.SELL, Events.OrderType.MARKET, 1);
        deliver(Topics.TRADES_EXECUTED, id, new Events.TradeExecuted(UUID.randomUUID(), sell, id, "TCS",
                Events.OrderSide.SELL, 1, BigDecimal.TEN, BigDecimal.TEN, Instant.now()));
        JsonNode ex = call(get("/api/readiness"), user(id), 200).at("/checklist/exercises");
        for (JsonNode e : ex) {
            assertThat(e.get("completedAt").isNull()).as(e.get("slug").asString()).isTrue();
        }
        // A LIVE event can never be evidence: the codec refuses it.
        EventCodec live = new EventCodec(json, jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator(),
                java.time.Clock.systemUTC(), "live", TradingEnvironment.LIVE, null);
        String liveEvent = live.encode(live.wrap(Topics.ORDERS_PLACED, "1", new Events.OrderPlaced(UUID.randomUUID(), id,
                "TCS", Events.OrderSide.BUY, Events.OrderType.MARKET, 1, BigDecimal.TEN)));
        assertThatThrownBy(() -> listener.onPlaced(liveEvent)).hasMessageContaining("Refusing LIVE event");
    }

    @Test
    void failedAttemptsHaveACooldownAndTheThirdGoesToAnAuthorisedReviewer() throws Exception {
        long id = reachAssessment();
        assertThat(takeFinal(id, 10).get("passed").asBoolean()).isFalse();
        // Immediate retake: refused with Retry-After (24 h pause).
        mvc.perform(post("/api/readiness/assessment/attempts").header("Authorization", user(id)))
                .andExpect(status().isTooManyRequests())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().exists("Retry-After"));
        ageFinalAttempts(id);
        assertThat(takeFinal(id, 10).get("passed").asBoolean()).isFalse();
        ageFinalAttempts(id);
        assertThat(takeFinal(id, 10).get("passed").asBoolean()).isFalse();
        assertThat(state(id)).isEqualTo("REVIEW_REQUIRED");
        call(post("/api/readiness/assessment/attempts"), user(id), 409);

        String decision = "{\"decision\":\"REQUIRE_REMEDIATION\",\"notes\":\"Missed most risk questions; redo risk management.\",\"remediationModules\":[\"risk-management\"]}";
        // A regular user cannot review; a reviewer cannot review themselves.
        postJson("/api/admin/readiness/reviews/" + id + "/decision", user(5), decision, 403);
        postJson("/api/admin/readiness/reviews/" + id + "/decision", TestJwts.bearer(TestJwts.token(id, "READINESS_REVIEWER")), decision, 403);
        JsonNode queue = call(get("/api/admin/readiness/reviews"), TestJwts.bearer(TestJwts.token(9, "READINESS_REVIEWER")), 200);
        assertThat(queue).anySatisfy(c -> assertThat(c.get("userId").asLong()).isEqualTo(id));
        JsonNode reviewCase = call(get("/api/admin/readiness/reviews/" + id), TestJwts.bearer(TestJwts.token(9, "READINESS_REVIEWER")), 200);
        assertThat(reviewCase.get("failedAttempts").asInt()).isEqualTo(3);

        postJson("/api/admin/readiness/reviews/" + id + "/decision", TestJwts.bearer(TestJwts.token(9, "READINESS_REVIEWER")), decision, 200);
        JsonNode s = call(get("/api/readiness"), user(id), 200);
        assertThat(s.get("state").asString()).isEqualTo("IN_PROGRESS");
        assertThat(s.get("nextStep").asString()).contains("risk-management");

        // Redoing the named module's quiz returns the user to the assessment with a fresh set of attempts.
        Catalog.Module risk = catalog.module("risk-management");
        JsonNode attempt = call(post("/api/academy/modules/risk-management/quiz/attempts"), user(id), 200);
        postJson("/api/academy/attempts/" + attempt.get("id").asString() + "/submit", user(id), answers(attempt, risk.questions(), 0), 200);
        assertThat(state(id)).isEqualTo("ASSESSMENT_PENDING");
        assertThat(call(get("/api/readiness"), user(id), 200).at("/checklist/finalAssessment/failedAttempts").asInt()).isZero();
        qualify(id);

        JsonNode admin = call(get("/api/admin/readiness/users/" + id), TestJwts.bearer(TestJwts.admin(1)), 200);
        assertThat(admin.get("history")).anySatisfy(h -> {
            assertThat(h.get("reasonCode").asString()).isEqualTo("REVIEW_REQUIRED_REMEDIATION");
            assertThat(h.get("actorType").asString()).isEqualTo("REVIEWER");
        });
    }

    @Test
    void selfExclusionRevocationExpiryAndPolicyChangesAllCloseEligibility() throws Exception {
        long id = reachAssessment();
        qualify(id);

        postJson("/api/readiness/self-exclusion", user(id), "{\"days\":30}", 200);
        assertThat(eligibility(id).get("reasons").toString()).contains("SELF_EXCLUDED");
        postJson("/api/readiness/self-exclusion", user(id), "{\"days\":1}", 409); // cannot be shortened
        jdbc.update("update readiness_status set self_excluded_until = null where user_id = ?", id);
        assertThat(eligibility(id).get("eligible").asBoolean()).isTrue();

        // Admin revocation: closed until an admin reinstates; then the user must requalify.
        postJson("/api/admin/readiness/users/" + id + "/revoke", user(5), "{\"reason\":\"Suspicious activity reported\"}", 403);
        postJson("/api/admin/readiness/users/" + id + "/revoke", TestJwts.bearer(TestJwts.admin(1)), "{\"reason\":\"Suspicious activity reported\"}", 200);
        assertThat(eligibility(id).get("eligible").asBoolean()).isFalse();
        assertThat(state(id)).isEqualTo("ACCESS_REVOKED");
        postJson("/api/admin/readiness/users/" + id + "/reinstate", TestJwts.bearer(TestJwts.admin(1)), "{\"reason\":\"Cleared after review\"}", 200);
        assertThat(state(id)).isEqualTo("ASSESSMENT_PENDING");
        qualify(id);

        // Expiry is evaluated on read: no job needs to have run.
        jdbc.update("update readiness_status set expires_at = ? where user_id = ?", java.sql.Timestamp.from(Instant.now().minusSeconds(1)), id);
        JsonNode expired = eligibility(id);
        assertThat(expired.get("eligible").asBoolean()).isFalse();
        assertThat(state(id)).isEqualTo("ASSESSMENT_PENDING"); // requalification path
        ageFinalAttempts(id);
        qualify(id);

        // A new policy that requires requalification revokes existing qualifications.
        postJson("/api/admin/readiness/policies", TestJwts.bearer(TestJwts.admin(1)), "{\"modulePassMarkPct\":80,\"finalPassMarkPct\":85,"
                + "\"finalRetakeCooldownHours\":24,\"maxFailedFinalAttempts\":3,\"qualificationValidityDays\":365,"
                + "\"requiredExercises\":[\"market-order\",\"limit-order-cancel\",\"stop-loss\",\"position-sizing\"],"
                + "\"acknowledgementVersion\":1,\"requiresRequalification\":true,\"notes\":\"Test: requalify everyone\"}", 200);
        assertThat(eligibility(id).get("eligible").asBoolean()).isFalse();
        JsonNode admin = call(get("/api/admin/readiness/users/" + id), TestJwts.bearer(TestJwts.admin(1)), 200);
        assertThat(admin.get("history")).anySatisfy(h -> assertThat(h.get("reasonCode").asString()).isEqualTo("POLICY_CHANGED"));
        assertThat(admin.at("/chain/intact").asBoolean()).isTrue();
    }

    @Test
    void tamperingWithTheAuditLogIsDetected() throws Exception {
        long id = USERS.incrementAndGet();
        call(post("/api/academy/modules/stock-market-fundamentals/lessons/what-is-a-stock/complete"), user(id), 204);
        assertThat(state(id)).isEqualTo("IN_PROGRESS");
        assertThat(readiness.verifyChain(id).intact()).isTrue();
        // (On PostgreSQL a trigger forbids this UPDATE outright; H2 has no trigger, so verify detection.)
        jdbc.update("update readiness_events set reason_code = 'FORGED' where user_id = ? and seq = 2", id);
        ReadinessService.ChainVerification v = readiness.verifyChain(id);
        assertThat(v.intact()).isFalse();
        assertThat(v.firstBrokenSeq()).isEqualTo(2);
    }

    @Test
    void stateMachineRejectsIllegalMoves() {
        assertThat(ReadinessState.NOT_STARTED.canMoveTo(ReadinessState.QUALIFIED)).isFalse();
        assertThat(ReadinessState.IN_PROGRESS.canMoveTo(ReadinessState.QUALIFIED)).isFalse();
        assertThat(ReadinessState.ASSESSMENT_PENDING.canMoveTo(ReadinessState.QUALIFIED)).isFalse();
        assertThat(ReadinessState.REVIEW_REQUIRED.canMoveTo(ReadinessState.QUALIFIED)).isFalse();
        assertThat(ReadinessState.ACCESS_REVOKED.canMoveTo(ReadinessState.QUALIFIED)).isFalse();
        // Only one edge leads to QUALIFIED.
        assertThat(java.util.Arrays.stream(ReadinessState.values()).filter(s -> s.canMoveTo(ReadinessState.QUALIFIED)))
                .containsExactly(ReadinessState.ACKNOWLEDGEMENT_PENDING);
        assertThatThrownBy(() -> {
            throw new InconsistentStateException("unused");
        }).isInstanceOf(InconsistentStateException.class);
    }
}
