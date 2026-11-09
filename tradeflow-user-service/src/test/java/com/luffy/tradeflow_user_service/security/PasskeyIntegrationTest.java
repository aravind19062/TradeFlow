package com.luffy.tradeflow_user_service.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The rules around passkeys. A real browser authenticator is exercised in e2e/profile.spec.ts. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PasskeyIntegrationTest {

    private static final String PASSWORD = "Secret#123";
    private static final String GARBAGE = "AAAA";

    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired JdbcTemplate jdbc;

    record Account(long id, String token) {
    }

    private Account register() throws Exception {
        String email = "pk-" + UUID.randomUUID().toString().substring(0, 8) + "@test.dev";
        JsonNode n = json.readTree(mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"T\",\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        return new Account(n.at("/user/id").asLong(), n.get("accessToken").asString());
    }

    private String enrollMfa(Account a) throws Exception {
        String secret = json.readTree(send(a, "/api/auth/mfa/totp/enroll", "{\"password\":\"" + PASSWORD + "\"}", 200))
                .get("secret").asString();
        send(a, "/api/auth/mfa/totp/confirm", "{\"code\":\"" + code(a, secret) + "\"}", 201);
        return secret;
    }

    private String code(Account a, String secret) {
        jdbc.update("update user_totp set last_used_step = -1 where user_id = ?", a.id());
        return Totp.code(secret, Totp.step(Instant.now()));
    }

    private String send(Account a, String url, String body, int expected) throws Exception {
        var request = post(url).contentType(MediaType.APPLICATION_JSON).content(body);
        if (a != null) {
            request.header("Authorization", "Bearer " + a.token());
        }
        var response = mvc.perform(request).andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(expected);
        return response.getContentAsString();
    }

    private String reauth(Account a, String secret) {
        return "{\"password\":\"" + PASSWORD + "\",\"code\":\"" + code(a, secret) + "\"}";
    }

    private static String registerBody(String challengeId) {
        return "{\"challengeId\":\"" + challengeId + "\",\"name\":\"Phone\",\"clientDataJSON\":\"" + GARBAGE
                + "\",\"attestationObject\":\"" + GARBAGE + "\"}";
    }

    private static String loginBody(String challengeId) {
        return "{\"challengeId\":\"" + challengeId + "\",\"credentialId\":\"" + GARBAGE + "\",\"clientDataJSON\":\"" + GARBAGE
                + "\",\"authenticatorData\":\"" + GARBAGE + "\",\"signature\":\"" + GARBAGE + "\",\"userHandle\":\"" + GARBAGE + "\"}";
    }

    @Test
    void addingAPasskeyNeedsTwoFactorAndTheRightCredentials() throws Exception {
        Account a = register();
        send(a, "/api/auth/passkeys/register/options", "{\"password\":\"" + PASSWORD + "\",\"code\":\"123456\"}", 403);
        String secret = enrollMfa(a);
        send(a, "/api/auth/passkeys/register/options", "{\"password\":\"Wrong#1234\",\"code\":\"" + code(a, secret) + "\"}", 401);
        assertThat(jdbc.queryForObject("select count(*) from security_events where user_id = ? and type = 'PASSKEY_CHANGE_FAILED'",
                Integer.class, a.id())).isEqualTo(1);
    }

    @Test
    void optionsDemandUserVerificationAndDiscoverableKeys() throws Exception {
        Account a = register();
        String secret = enrollMfa(a);
        JsonNode options = json.readTree(send(a, "/api/auth/passkeys/register/options", reauth(a, secret), 200));
        JsonNode selection = options.at("/publicKey/authenticatorSelection");
        assertThat(selection.get("userVerification").asString()).isEqualTo("required");
        assertThat(selection.get("residentKey").asString()).isEqualTo("required");
        assertThat(options.at("/publicKey/rp/id").asString()).isEqualTo("localhost");
    }

    @Test
    void aForgedRegistrationIsRefusedAndItsChallengeCannotBeReused() throws Exception {
        Account a = register();
        String secret = enrollMfa(a);
        String id = json.readTree(send(a, "/api/auth/passkeys/register/options", reauth(a, secret), 200)).get("challengeId").asString();
        send(a, "/api/auth/passkeys/register", registerBody(id), 400);
        String second = send(a, "/api/auth/passkeys/register", registerBody(id), 400);
        assertThat(second).contains("PASSKEY_CHALLENGE_INVALID");
        assertThat(jdbc.queryForObject("select count(*) from user_passkeys where user_id = ?", Integer.class, a.id())).isZero();
    }

    @Test
    void oneUsersChallengeCannotBeUsedByAnother() throws Exception {
        Account a = register();
        String secret = enrollMfa(a);
        String id = json.readTree(send(a, "/api/auth/passkeys/register/options", reauth(a, secret), 200)).get("challengeId").asString();
        String response = send(register(), "/api/auth/passkeys/register", registerBody(id), 400);
        assertThat(response).contains("PASSKEY_VERIFICATION_FAILED");
    }

    @Test
    void signInIsPublicButFailsForAnUnknownPasskeyAndChallengesAreSingleUse() throws Exception {
        String id = json.readTree(send(null, "/api/auth/passkeys/login/options", "", 200)).get("challengeId").asString();
        send(null, "/api/auth/passkeys/login", loginBody(id), 401);
        assertThat(send(null, "/api/auth/passkeys/login", loginBody(id), 400)).contains("PASSKEY_CHALLENGE_INVALID");
        send(null, "/api/auth/passkeys/login", loginBody(UUID.randomUUID().toString()), 400);
    }

    @Test
    void aRegisterChallengeCannotSignIn() throws Exception {
        Account a = register();
        String secret = enrollMfa(a);
        String id = json.readTree(send(a, "/api/auth/passkeys/register/options", reauth(a, secret), 200)).get("challengeId").asString();
        assertThat(send(null, "/api/auth/passkeys/login", loginBody(id), 400)).contains("PASSKEY_CHALLENGE_INVALID");
    }

    @Test
    void managingPasskeysRequiresSignIn() throws Exception {
        mvc.perform(get("/api/auth/passkeys")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/passkeys/register/options").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        Account a = register();
        mvc.perform(get("/api/auth/passkeys").header("Authorization", "Bearer " + a.token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
    }
}
