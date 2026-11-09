package com.luffy.tradeflow_user_service.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SessionsActivityIntegrationTest {

    private static final String PASSWORD = "Secret#123";

    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;

    record Login(String token, String cookie) {
    }

    private String email() throws Exception {
        String email = "sess-" + UUID.randomUUID().toString().substring(0, 8) + "@test.dev";
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"T\",\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isCreated());
        return email;
    }

    private Login login(String email, String agent) throws Exception {
        MockHttpServletResponse r = mvc.perform(post("/api/auth/login").header("User-Agent", agent)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse();
        String cookie = r.getHeaders("Set-Cookie").stream().filter(h -> h.startsWith("tf_refresh=")).findFirst().orElseThrow();
        return new Login(json.readTree(r.getContentAsString()).get("accessToken").asString(),
                cookie.substring("tf_refresh=".length(), cookie.indexOf(';')));
    }

    private JsonNode getJson(String url, String token) throws Exception {
        return json.readTree(mvc.perform(get(url).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private int refresh(String cookie) throws Exception {
        return mvc.perform(post("/api/auth/refresh").header("X-TradeFlow-Client", "web")
                        .cookie(new jakarta.servlet.http.Cookie("tf_refresh", cookie)))
                .andReturn().getResponse().getStatus();
    }

    private String otherSessionId(JsonNode sessions) {
        for (JsonNode s : sessions) {
            if (!s.get("current").asBoolean()) {
                return s.get("id").asString();
            }
        }
        throw new AssertionError("no other session");
    }

    @Test
    void listsDevicesAndEndsAnotherOne() throws Exception {
        String email = email();
        Login laptop = login(email, "Laptop");
        Login phone = login(email, "Phone");

        JsonNode sessions = getJson("/api/auth/sessions", laptop.token());
        long registrationSessions = sessions.size();
        assertThat(registrationSessions).isGreaterThanOrEqualTo(2);
        assertThat(sessions.get(0).get("current").asBoolean()).isTrue();
        assertThat(sessions.get(0).get("userAgent").asString()).isEqualTo("Laptop");

        String phoneId = null;
        for (JsonNode s : sessions) {
            if ("Phone".equals(s.get("userAgent").asString())) {
                phoneId = s.get("id").asString();
            }
        }
        mvc.perform(delete("/api/auth/sessions/" + phoneId).header("Authorization", "Bearer " + laptop.token()))
                .andExpect(status().isNoContent());
        assertThat(refresh(phone.cookie())).isEqualTo(401);
        assertThat(refresh(laptop.cookie())).isEqualTo(200);
        assertThat(getJson("/api/auth/sessions", laptop.token()).size()).isEqualTo((int) registrationSessions - 1);
    }

    @Test
    void refusesToEndTheCurrentSessionOrSomeoneElses() throws Exception {
        String email = email();
        Login mine = login(email, "Mine");
        Login theirs = login(email(), "Theirs");
        JsonNode sessions = getJson("/api/auth/sessions", mine.token());
        String current = sessions.get(0).get("id").asString();
        mvc.perform(delete("/api/auth/sessions/" + current).header("Authorization", "Bearer " + mine.token()))
                .andExpect(status().isConflict());

        String victim = getJson("/api/auth/sessions", theirs.token()).get(0).get("id").asString();
        mvc.perform(delete("/api/auth/sessions/" + victim).header("Authorization", "Bearer " + mine.token()))
                .andExpect(status().isNotFound());
        assertThat(refresh(theirs.cookie())).isEqualTo(200);
    }

    @Test
    void activityIsPagedFilteredAndPrivate() throws Exception {
        String email = email();
        Login a = login(email, "A");
        login(email, "B");
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"Wrong#1234\"}")).andExpect(status().isUnauthorized());

        JsonNode failed = getJson("/api/auth/activity?category=failed", a.token());
        assertThat(failed.get("totalItems").asLong()).isEqualTo(1);
        assertThat(failed.at("/items/0/type").asString()).isEqualTo("LOGIN_FAILED");

        JsonNode firstPage = getJson("/api/auth/activity?size=1", a.token());
        assertThat(firstPage.get("items").size()).isEqualTo(1);
        assertThat(firstPage.get("totalPages").asInt()).isGreaterThan(1);

        Login other = login(email(), "Other");
        assertThat(getJson("/api/auth/activity?category=failed", other.token()).get("totalItems").asLong()).isZero();

        mvc.perform(get("/api/auth/activity?category=nope").header("Authorization", "Bearer " + a.token()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_CATEGORY"));
        mvc.perform(get("/api/auth/activity")).andExpect(status().isUnauthorized());
    }
}
