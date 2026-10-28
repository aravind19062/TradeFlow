package com.luffy.tradeflow_user_service;

import com.luffy.tradeflow.common.outbox.OutboxEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired JwtDecoder jwtDecoder;
    @Autowired OutboxEventRepository outbox;

    private JsonNode register(String email, String password) throws Exception {
        String body = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Asha\",\"email\":\"" + email + "\",\"password\":\"" + password
                                + "\",\"panNumber\":\"abcde1234f\",\"mobileNumber\":\"9876543210\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    @Test
    void registrationIssuesValidTokenAndPublishesEvent() throws Exception {
        long before = outbox.count();
        JsonNode response = register("asha@test.dev", "Secret#123");

        Jwt jwt = jwtDecoder.decode(response.get("accessToken").asString());
        assertThat(jwt.getSubject()).isEqualTo(response.at("/user/id").asString());
        assertThat(jwt.getClaimAsStringList("roles")).isEqualTo(List.of("USER"));
        assertThat(response.at("/user/password").isMissingNode()).isTrue();
        assertThat(outbox.count()).isEqualTo(before + 1);
        assertThat(outbox.findAll()).anySatisfy(e -> {
            assertThat(e.getTopic()).isEqualTo("users.registered");
            assertThat(e.getPayload()).contains("asha@test.dev").doesNotContain("Secret#123");
        });
    }

    @Test
    void duplicateEmailIsRejectedCaseInsensitively() throws Exception {
        register("dup@test.dev", "Secret#123");
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"X\",\"email\":\"DUP@test.dev\",\"password\":\"Secret#123\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_REGISTERED"));
    }

    @Test
    void registrationValidatesInput() throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"email\":\"not-an-email\",\"password\":\"short\",\"panNumber\":\"123\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.length()").value(4));
    }

    @Test
    void repeatedFailedLoginsAreThrottledWithRetryAfter() throws Exception {
        register("throttle@test.dev", "Secret#123");
        String wrong = "{\"email\":\"throttle@test.dev\",\"password\":\"wrong-pass\"}";
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(wrong))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"throttle@test.dev\",\"password\":\"Secret#123\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("TOO_MANY_LOGIN_ATTEMPTS"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .exists("Retry-After"));
    }

    @Test
    void healthDetailsAreHiddenFromAnonymousCallers() throws Exception {
        // No Kafka in the test profile, so overall status may be DOWN (503); only the body shape matters here.
        mvc.perform(get("/actuator/health"))
                .andExpect(jsonPath("$.status").exists())
                .andExpect(jsonPath("$.components").doesNotExist());
        mvc.perform(get("/actuator/health").header("Authorization",
                        "Bearer " + com.luffy.tradeflow.common.testsupport.TestJwts.admin(1)))
                .andExpect(jsonPath("$.components").exists());
    }

    @Test
    void loginSucceedsAndFailuresDoNotRevealWhichPartWasWrong() throws Exception {
        register("login@test.dev", "Secret#123");
        String token = json.readTree(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"LOGIN@test.dev\",\"password\":\"Secret#123\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .get("accessToken").asString();

        String wrongPassword = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"login@test.dev\",\"password\":\"nope-nope\"}"))
                .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
        String unknownUser = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"ghost@test.dev\",\"password\":\"nope-nope\"}"))
                .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(wrongPassword).get("detail")).isEqualTo(json.readTree(unknownUser).get("detail"));

        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("login@test.dev"))
                .andExpect(content().string(not(containsString("password"))));
        mvc.perform(get("/api/users/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + token + "x"))
                .andExpect(status().isUnauthorized());
    }
}
