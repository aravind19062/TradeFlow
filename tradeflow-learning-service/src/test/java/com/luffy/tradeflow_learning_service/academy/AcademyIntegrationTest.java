package com.luffy.tradeflow_learning_service.academy;

import com.luffy.tradeflow.common.testsupport.TestJwts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AcademyIntegrationTest {

    private static final AtomicLong USERS = new AtomicLong(5_000);
    private static final String MODULE = "bid-ask-spread";

    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired ContentCatalog catalog;

    private String auth(long user) {
        return TestJwts.bearer(TestJwts.user(user));
    }

    private JsonNode call(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder b, int expected) throws Exception {
        String body = mvc.perform(b).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return body.isBlank() ? null : json.readTree(body);
    }

    private void readAllLessons(long user, String module) throws Exception {
        for (Catalog.Lesson l : catalog.module(module).lessons()) {
            call(post("/api/academy/modules/" + module + "/lessons/" + l.slug() + "/complete").header("Authorization", auth(user)), 204);
        }
    }

    /** Answers built from the server-side answer key (the test has access; clients never do). */
    private String answers(JsonNode attempt, boolean allCorrect) {
        List<String> parts = new ArrayList<>();
        boolean first = true;
        for (JsonNode q : attempt.get("questions")) {
            Catalog.Question question = catalog.module(MODULE).questions().stream()
                    .filter(x -> x.key().equals(q.get("key").asString())).findFirst().orElseThrow();
            List<String> keys = new ArrayList<>(question.correctKeys());
            if (!allCorrect && first) {
                keys = List.of(question.options().stream().filter(o -> !o.correct()).findFirst().orElseThrow().key());
            }
            first = false;
            parts.add("{\"questionKey\":\"" + question.key() + "\",\"optionKeys\":[\"" + String.join("\",\"", keys) + "\"]}");
        }
        return "{\"answers\":[" + String.join(",", parts) + "]}";
    }

    @Test
    void overviewShowsTenModulesAndProgress() throws Exception {
        long user = USERS.incrementAndGet();
        JsonNode overview = call(get("/api/academy/modules").header("Authorization", auth(user)), 200);
        assertThat(overview.get("modules")).hasSize(10);
        assertThat(overview.get("modulePassMarkPct").asInt()).isEqualTo(80);
        assertThat(overview.get("policyVersion").asInt()).isEqualTo(1);
        assertThat(overview.at("/modules/0/status").asString()).isEqualTo("NOT_STARTED");

        JsonNode lesson = call(get("/api/academy/modules/stock-market-fundamentals/lessons/what-is-a-stock")
                .header("Authorization", auth(user)), 200);
        assertThat(lesson.get("body").asString()).contains("ownership");
        assertThat(lesson.get("nextLesson").asString()).isEqualTo("exchanges-and-participants");
        call(post("/api/academy/modules/stock-market-fundamentals/lessons/what-is-a-stock/complete")
                .header("Authorization", auth(user)), 204);
        call(post("/api/academy/modules/stock-market-fundamentals/lessons/what-is-a-stock/complete")
                .header("Authorization", auth(user)), 204); // idempotent
        assertThat(call(get("/api/academy/modules/stock-market-fundamentals").header("Authorization", auth(user)), 200)
                .get("status").asString()).isEqualTo("IN_PROGRESS");
        mvc.perform(get("/api/academy/modules")).andExpect(status().isUnauthorized());
    }

    @Test
    void quizIsLockedUntilLessonsAreReadAndNeverRevealsAnswersBeforeSubmission() throws Exception {
        long user = USERS.incrementAndGet();
        mvc.perform(post("/api/academy/modules/" + MODULE + "/quiz/attempts").header("Authorization", auth(user)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LESSONS_INCOMPLETE"));
        readAllLessons(user, MODULE);

        String raw = mvc.perform(post("/api/academy/modules/" + MODULE + "/quiz/attempts").header("Authorization", auth(user)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(raw).doesNotContain("\"correct\"").doesNotContain("explanation");
        JsonNode attempt = json.readTree(raw);
        assertThat(attempt.get("questions")).hasSize(5);
        // Resuming returns the same open attempt, not a fresh draw.
        JsonNode again = call(post("/api/academy/modules/" + MODULE + "/quiz/attempts").header("Authorization", auth(user)), 200);
        assertThat(again.get("id").asString()).isEqualTo(attempt.get("id").asString());
        // Another user cannot see or submit it.
        mvc.perform(get("/api/academy/attempts/" + attempt.get("id").asString()).header("Authorization", auth(user + 1000)))
                .andExpect(status().isNotFound());

        JsonNode result = call(post("/api/academy/attempts/" + attempt.get("id").asString() + "/submit")
                .header("Authorization", auth(user)).contentType(MediaType.APPLICATION_JSON).content(answers(attempt, false)), 200);
        assertThat(result.get("scorePct").asInt()).isEqualTo(80);
        assertThat(result.get("passed").asBoolean()).isTrue(); // 4/5 = 80% meets the 80% pass mark
        assertThat(result.at("/questions/0/explanation").asString()).isNotBlank();
        assertThat(result.get("policyVersion").asInt()).isEqualTo(1);
        mvc.perform(post("/api/academy/attempts/" + attempt.get("id").asString() + "/submit").header("Authorization", auth(user))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"answers\":[]}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ATTEMPT_ALREADY_SUBMITTED"));
        assertThat(call(get("/api/academy/modules/" + MODULE).header("Authorization", auth(user)), 200)
                .get("status").asString()).isEqualTo("COMPLETED");
    }

    @Test
    void failingBelowThePassMarkDoesNotCompleteTheModule() throws Exception {
        long user = USERS.incrementAndGet();
        readAllLessons(user, MODULE);
        JsonNode attempt = call(post("/api/academy/modules/" + MODULE + "/quiz/attempts").header("Authorization", auth(user)), 200);
        JsonNode result = call(post("/api/academy/attempts/" + attempt.get("id").asString() + "/submit")
                .header("Authorization", auth(user)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"answers\":[]}"), 200);
        assertThat(result.get("scorePct").asInt()).isZero();
        assertThat(result.get("passed").asBoolean()).isFalse();
        assertThat(call(get("/api/academy/modules/" + MODULE).header("Authorization", auth(user)), 200)
                .get("status").asString()).isEqualTo("QUIZ_AVAILABLE");
    }
}
