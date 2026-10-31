package com.luffy.tradeflow_user_service.security;

import com.luffy.tradeflow.common.security.JwtProperties;
import com.luffy.tradeflow.common.security.JwtSecurityConfig;
import com.luffy.tradeflow.common.security.PublicKeyRing;
import com.luffy.tradeflow.common.testsupport.TestJwts;
import com.luffy.tradeflow_user_service.entity.User;
import com.luffy.tradeflow_user_service.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 2 security behaviour end to end through the HTTP API: rotating refresh sessions with
 * reuse detection, sign-out everywhere, TOTP enrolment and MFA sign-in (with replay
 * protection and recovery codes), step-up for live trading, the audited admin MFA reset and
 * service client credentials.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthHardeningIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired JwtDecoder jwtDecoder;
    @Autowired UserRepository users;
    @Autowired SecurityEventRepository events;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    private final JwtDecoder liveDecoder = JwtSecurityConfig.decoder(PublicKeyRing.load(TestJwts.PUBLIC_KEYS),
            TestJwts.ISSUER, JwtProperties.LIVE_AUDIENCE);

    // ------------------------------------------------------------------ helpers

    record Session(long userId, String accessToken, String refreshCookie) {
    }

    private static String email(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8) + "@test.dev";
    }

    private Session register(String email) throws Exception {
        MockHttpServletResponse r = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"T\",\"email\":\"" + email + "\",\"password\":\"Secret#123\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse();
        JsonNode body = json.readTree(r.getContentAsString());
        return new Session(body.at("/user/id").asLong(), body.get("accessToken").asString(), refreshCookie(r));
    }

    private static String refreshCookie(MockHttpServletResponse r) {
        for (String h : r.getHeaders("Set-Cookie")) {
            if (h.startsWith("tf_refresh=")) {
                assertThat(h).contains("HttpOnly").contains("SameSite=Strict").contains("Path=/api/auth");
                return h.substring("tf_refresh=".length(), h.indexOf(';'));
            }
        }
        return null;
    }

    private MockHttpServletRequestBuilder withCookie(MockHttpServletRequestBuilder b, String cookie) {
        return b.cookie(new jakarta.servlet.http.Cookie("tf_refresh", cookie)).header("X-TradeFlow-Client", "web");
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private JsonNode postJson(String url, String token, String body, int expected) throws Exception {
        var b = post(url).contentType(MediaType.APPLICATION_JSON).content(body);
        if (token != null) {
            b = b.header("Authorization", bearer(token));
        }
        String out = mvc.perform(b).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return out.isBlank() ? null : json.readTree(out);
    }

    /** Enrols TOTP and returns [secret, step used for confirmation, recovery codes...]. */
    private List<String> enrollMfa(Session s) throws Exception {
        postJson("/api/auth/mfa/totp/enroll", s.accessToken(), "{\"password\":\"wrong-password\"}", 401);
        JsonNode enrollment = postJson("/api/auth/mfa/totp/enroll", s.accessToken(), "{\"password\":\"Secret#123\"}", 200);
        String secret = enrollment.get("secret").asString();
        assertThat(enrollment.get("otpauthUri").asString()).startsWith("otpauth://totp/TradeFlow:");
        long step = Totp.step(Instant.now());
        postJson("/api/auth/mfa/totp/confirm", s.accessToken(), "{\"code\":\"000000\"}".replace("000000",
                wrongCode(secret, step)), 422);
        JsonNode codes = postJson("/api/auth/mfa/totp/confirm", s.accessToken(),
                "{\"code\":\"" + Totp.code(secret, step) + "\"}", 201);
        List<String> out = new ArrayList<>(List.of(secret, String.valueOf(step)));
        codes.get("recoveryCodes").forEach(c -> out.add(c.asString()));
        return out;
    }

    private static String wrongCode(String secret, long step) {
        String right = Totp.code(secret, step);
        return right.equals("000000") ? "111111" : "000000";
    }

    // ------------------------------------------------------------------ sessions

    @Test
    void refreshRotatesAndReusingAnOldRefreshTokenEndsTheSession() throws Exception {
        Session s = register(email("rot"));
        assertThat(s.refreshCookie()).isNotBlank();

        mvc.perform(post("/api/auth/refresh").cookie(new jakarta.servlet.http.Cookie("tf_refresh", s.refreshCookie())))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CLIENT_HEADER_REQUIRED"));

        MockHttpServletResponse first = mvc.perform(withCookie(post("/api/auth/refresh"), s.refreshCookie()))
                .andExpect(status().isOk()).andReturn().getResponse();
        String rotated = refreshCookie(first);
        assertThat(rotated).isNotEqualTo(s.refreshCookie());
        Jwt refreshed = jwtDecoder.decode(json.readTree(first.getContentAsString()).get("accessToken").asString());
        assertThat(refreshed.getClaimAsString("sid")).isEqualTo(jwtDecoder.decode(s.accessToken()).getClaimAsString("sid"));

        // Presented again immediately (two tabs refreshing at once): refused, but nothing is revoked
        // and no token is issued.
        mvc.perform(withCookie(post("/api/auth/refresh"), s.refreshCookie()))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("REFRESH_RACE"))
                .andExpect(jsonPath("$.accessToken").doesNotExist());
        assertThat(events.findByUserIdAndTypeOrderByOccurredAtDesc(s.userId(), "REFRESH_REUSE_DETECTED")).isEmpty();

        // The old token comes back later (stolen copy): the whole session is revoked.
        jdbc.update("update refresh_tokens set used_at = used_at - interval '1' minute where token_hash = ?",
                SessionService.hash(s.refreshCookie()));
        mvc.perform(withCookie(post("/api/auth/refresh"), s.refreshCookie()))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("SESSION_EXPIRED"));
        mvc.perform(withCookie(post("/api/auth/refresh"), rotated)).andExpect(status().isUnauthorized());
        assertThat(events.findByUserIdAndTypeOrderByOccurredAtDesc(s.userId(), "REFRESH_REUSE_DETECTED")).hasSize(1);
    }

    @Test
    void logoutRevokesTheSessionAndClearsTheCookie() throws Exception {
        Session s = register(email("out"));
        MockHttpServletResponse r = mvc.perform(withCookie(post("/api/auth/logout"), s.refreshCookie()))
                .andExpect(status().isNoContent()).andReturn().getResponse();
        assertThat(r.getHeader("Set-Cookie")).startsWith("tf_refresh=;").contains("Max-Age=0");
        mvc.perform(withCookie(post("/api/auth/refresh"), s.refreshCookie())).andExpect(status().isUnauthorized());
    }

    @Test
    void signOutEverywhereInvalidatesAllSessionsAndStepUp() throws Exception {
        String mail = email("all");
        Session a = register(mail);
        JsonNode login = postJson("/api/auth/login", null, "{\"email\":\"" + mail + "\",\"password\":\"Secret#123\"}", 200);
        assertThat(login.get("mfaRequired").asBoolean()).isFalse();

        postJson("/api/auth/logout-all", a.accessToken(), "", 204);
        mvc.perform(withCookie(post("/api/auth/refresh"), a.refreshCookie())).andExpect(status().isUnauthorized());
        // An access token from before the sign-out can no longer obtain a step-up token.
        postJson("/api/auth/step-up", login.get("accessToken").asString(),
                "{\"password\":\"Secret#123\",\"code\":\"123456\"}", 401);
        assertThat(users.findById(a.userId()).orElseThrow().getTokenVersion()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ MFA

    @Test
    void mfaLoginRequiresTheSecondFactorAndRefusesReplayedCodes() throws Exception {
        String mail = email("mfa");
        Session s = register(mail);
        List<String> mfa = enrollMfa(s);
        String secret = mfa.get(0);
        long usedStep = Long.parseLong(mfa.get(1));
        assertThat(mfa.subList(2, mfa.size())).hasSize(10).allMatch(c -> c.matches("[A-Z2-7]{5}-[A-Z2-7]{5}"));

        // Password alone: no tokens, no cookie, only a challenge.
        MockHttpServletResponse pw = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + mail + "\",\"password\":\"Secret#123\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.mfaRequired").value(true))
                .andExpect(jsonPath("$.accessToken").doesNotExist()).andReturn().getResponse();
        assertThat(pw.getHeader("Set-Cookie")).isNull();
        String challenge = json.readTree(pw.getContentAsString()).get("mfaToken").asString();

        postJson("/api/auth/login/mfa", null, "{\"mfaToken\":\"" + challenge + "\",\"code\":\""
                + wrongCode(secret, usedStep + 1) + "\"}", 401);
        // The code already used at enrolment is refused (replay), the next one is accepted.
        postJson("/api/auth/login/mfa", null, "{\"mfaToken\":\"" + challenge + "\",\"code\":\""
                + Totp.code(secret, usedStep) + "\"}", 401);
        JsonNode ok = postJson("/api/auth/login/mfa", null, "{\"mfaToken\":\"" + challenge + "\",\"code\":\""
                + Totp.code(secret, usedStep + 1) + "\"}", 200);
        assertThat(jwtDecoder.decode(ok.get("accessToken").asString()).getClaimAsStringList("amr"))
                .containsExactly("pwd", "otp");
        // The challenge is single-use.
        postJson("/api/auth/login/mfa", null, "{\"mfaToken\":\"" + challenge + "\",\"code\":\""
                + Totp.code(secret, usedStep + 1) + "\"}", 401);
    }

    @Test
    void recoveryCodesWorkOnceAndMfaCannotBeDisabledWithoutASecondFactor() throws Exception {
        String mail = email("rec");
        Session s = register(mail);
        List<String> mfa = enrollMfa(s);
        String recovery = mfa.get(2);

        String challenge = postJson("/api/auth/login", null, "{\"email\":\"" + mail + "\",\"password\":\"Secret#123\"}", 200)
                .get("mfaToken").asString();
        JsonNode ok = postJson("/api/auth/login/mfa", null, "{\"mfaToken\":\"" + challenge + "\",\"recoveryCode\":\""
                + recovery.toLowerCase() + "\"}", 200);
        assertThat(jwtDecoder.decode(ok.get("accessToken").asString()).getClaimAsStringList("amr")).contains("rec");
        assertThat(events.findByUserIdAndTypeOrderByOccurredAtDesc(s.userId(), "RECOVERY_CODE_USED")).hasSize(1);

        String again = postJson("/api/auth/login", null, "{\"email\":\"" + mail + "\",\"password\":\"Secret#123\"}", 200)
                .get("mfaToken").asString();
        postJson("/api/auth/login/mfa", null, "{\"mfaToken\":\"" + again + "\",\"recoveryCode\":\"" + recovery + "\"}", 401);

        String token = ok.get("accessToken").asString();
        postJson("/api/auth/mfa/totp/disable", token, "{\"password\":\"Secret#123\"}", 401);
        mvc.perform(get("/api/auth/mfa").header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.enabled").value(true)).andExpect(jsonPath("$.recoveryCodesRemaining").value(9));
    }

    // ------------------------------------------------------------------ step-up

    @Test
    void stepUpRequiresMfaPasswordAndCodeAndYieldsALiveOnlyToken() throws Exception {
        Session s = register(email("step"));
        postJson("/api/auth/step-up", s.accessToken(), "{\"password\":\"Secret#123\",\"code\":\"123456\"}", 403);

        List<String> mfa = enrollMfa(s);
        String secret = mfa.get(0);
        long step = Long.parseLong(mfa.get(1));
        postJson("/api/auth/step-up", s.accessToken(), "{\"password\":\"wrong\",\"code\":\""
                + Totp.code(secret, step + 1) + "\"}", 401);
        JsonNode stepUp = postJson("/api/auth/step-up", s.accessToken(), "{\"password\":\"Secret#123\",\"code\":\""
                + Totp.code(secret, step + 1) + "\"}", 200);
        String token = stepUp.get("stepUpToken").asString();
        assertThat(stepUp.get("expiresInSeconds").asLong()).isBetween(500L, 600L);

        Jwt live = liveDecoder.decode(token);
        assertThat(live.getClaimAsStringList("amr")).containsExactly("pwd", "otp");
        assertThat(live.getClaimAsString("scope")).isEqualTo("live");
        // A live token is useless for the ordinary APIs, and an ordinary token is useless for live ones.
        assertThatThrownBy(() -> jwtDecoder.decode(token)).isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> liveDecoder.decode(s.accessToken())).isInstanceOf(JwtException.class);
    }

    // ------------------------------------------------------------------ admin recovery

    @Test
    void adminMfaResetIsAuditedEndsSessionsAndHoldsLiveTrading() throws Exception {
        Session user = register(email("victim"));
        List<String> userMfa = enrollMfa(user);

        String adminMail = email("admin");
        Session admin = register(adminMail);
        User adminEntity = users.findById(admin.userId()).orElseThrow();
        adminEntity.setRole(User.ROLE_ADMIN);
        users.save(adminEntity);
        String adminToken = postJson("/api/auth/login", null, "{\"email\":\"" + adminMail + "\",\"password\":\"Secret#123\"}", 200)
                .get("accessToken").asString();
        String body = "{\"reason\":\"Lost phone and recovery codes, verified by video call\",\"verificationReference\":\"TICKET-42\",\"adminCode\":\"%s\"}";

        // A regular user cannot; an admin without their own MFA cannot.
        postJson("/api/admin/users/" + user.userId() + "/mfa/reset", user.accessToken(), body.formatted("123456"), 403);
        postJson("/api/admin/users/" + user.userId() + "/mfa/reset", adminToken, body.formatted("123456"), 403);

        List<String> adminMfa = enrollMfa(new Session(admin.userId(), adminToken, null));
        long adminStep = Long.parseLong(adminMfa.get(1));
        postJson("/api/admin/users/" + admin.userId() + "/mfa/reset", adminToken,
                body.formatted(Totp.code(adminMfa.get(0), adminStep + 1)), 403); // not on themselves
        postJson("/api/admin/users/" + user.userId() + "/mfa/reset", adminToken,
                body.formatted(Totp.code(adminMfa.get(0), adminStep + 1)), 204);

        // The user's sessions are gone, MFA is off, and both accounts carry the audit record.
        mvc.perform(withCookie(post("/api/auth/refresh"), user.refreshCookie())).andExpect(status().isUnauthorized());
        assertThat(events.findByUserIdAndTypeOrderByOccurredAtDesc(user.userId(), "MFA_RESET_BY_ADMIN"))
                .singleElement().satisfies(e -> {
                    assertThat(e.getActorId()).isEqualTo(admin.userId());
                    assertThat(e.getDetail()).contains("TICKET-42");
                });
        assertThat(events.findByUserIdAndTypeOrderByOccurredAtDesc(admin.userId(), "MFA_RESET_BY_ADMIN")).hasSize(1);

        // No bypass: the user still needs the password, re-enrols, and live step-up is held for 24 h.
        String fresh = postJson("/api/auth/login", null, "{\"email\":\"" + users.findById(user.userId()).orElseThrow().getMailId()
                + "\",\"password\":\"Secret#123\"}", 200).get("accessToken").asString();
        List<String> again = enrollMfa(new Session(user.userId(), fresh, null));
        JsonNode held = postJson("/api/auth/step-up", fresh, "{\"password\":\"Secret#123\",\"code\":\""
                + Totp.code(again.get(0), Long.parseLong(again.get(1)) + 1) + "\"}", 403);
        assertThat(held.get("code").asString()).isEqualTo("STEP_UP_ON_HOLD");
        assertThat(userMfa.get(0)).isNotEqualTo(again.get(0));
    }

    // ------------------------------------------------------------------ service clients

    @Test
    void servicesObtainServiceTokensOnlyWithTheirClientSecret() throws Exception {
        String good = Base64.getEncoder().encodeToString(
                "tradeflow-portfolio-service:test-only-portfolio-client-secret-0123".getBytes(StandardCharsets.UTF_8));
        JsonNode token = json.readTree(mvc.perform(post("/internal/auth/service-token").header("Authorization", "Basic " + good))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        Jwt jwt = jwtDecoder.decode(token.get("accessToken").asString());
        assertThat(jwt.getSubject()).isEqualTo("service:tradeflow-portfolio-service");
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("SERVICE");

        String wrong = Base64.getEncoder().encodeToString("tradeflow-portfolio-service:guess".getBytes(StandardCharsets.UTF_8));
        mvc.perform(post("/internal/auth/service-token").header("Authorization", "Basic " + wrong))
                .andExpect(status().isUnauthorized());
        String unknown = Base64.getEncoder().encodeToString("rogue:test-only-portfolio-client-secret-0123".getBytes(StandardCharsets.UTF_8));
        mvc.perform(post("/internal/auth/service-token").header("Authorization", "Basic " + unknown))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/auth/service-token")).andExpect(status().isUnauthorized());
    }

    @Test
    void usersCanSeeTheirSecurityActivity() throws Exception {
        String mail = email("act");
        Session s = register(mail);
        postJson("/api/auth/login", null, "{\"email\":\"" + mail + "\",\"password\":\"nope-nope\"}", 401);
        mvc.perform(get("/api/auth/mfa/security-events").header("Authorization", bearer(s.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("LOGIN_FAILED"));
    }
}
