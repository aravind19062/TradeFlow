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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Profile edits and the protections on bank details, through the HTTP API. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProfileBankIntegrationTest {

    private static final String PASSWORD = "Secret#123";
    private static final String NUMBER = "123456789012";

    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.luffy.tradeflow.common.crypto.DataEncryptor encryptor;

    record Account(long id, String token, String email) {
    }

    private Account register() throws Exception {
        String email = "profile-" + UUID.randomUUID().toString().substring(0, 8) + "@test.dev";
        String body = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"T\",\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        JsonNode n = json.readTree(body);
        return new Account(n.at("/user/id").asLong(), n.get("accessToken").asString(), email);
    }

    private String enrollMfa(Account a) throws Exception {
        String body = mvc.perform(post("/api/auth/mfa/totp/enroll").header("Authorization", "Bearer " + a.token())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"" + PASSWORD + "\"}"))
                .andReturn().getResponse().getContentAsString();
        String secret = json.readTree(body).get("secret").asString();
        mvc.perform(post("/api/auth/mfa/totp/confirm").header("Authorization", "Bearer " + a.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code(a, secret) + "\"}"))
                .andExpect(status().isCreated());
        return secret;
    }

    /** A valid code; clears replay protection so one test can use several within 30 seconds. */
    private String code(Account a, String secret) {
        jdbc.update("update user_totp set last_used_step = -1 where user_id = ?", a.id());
        return Totp.code(secret, Totp.step(Instant.now()));
    }

    private static String bankBody(String number, String password, String code) {
        return "{\"accountHolderName\":\"T User\",\"bankName\":\"HDFC Bank\",\"accountNumber\":\"" + number
                + "\",\"ifsc\":\"hdfc0001234\",\"accountType\":\"SAVINGS\",\"password\":\"" + password
                + "\",\"code\":\"" + code + "\"}";
    }

    private int saveBank(Account a, String body) throws Exception {
        return mvc.perform(put("/api/users/me/bank-account").header("Authorization", "Bearer " + a.token())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse().getStatus();
    }

    private int bankRows(Account a) {
        return jdbc.queryForObject("select count(*) from user_bank_accounts where user_id = ?", Integer.class, a.id());
    }

    private int events(Account a, String type) {
        return jdbc.queryForObject("select count(*) from security_events where user_id = ? and type = ?",
                Integer.class, a.id(), type);
    }

    @Test
    void updatesDetailsButNeverEmailOrRole() throws Exception {
        Account a = register();
        mvc.perform(put("/api/users/me/profile").header("Authorization", "Bearer " + a.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\" New Name \",\"mobileNumber\":\"+919876543210\",\"occupation\":\"  \","
                                + "\"email\":\"attacker@test.dev\",\"role\":\"ADMIN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("New Name"))
                .andExpect(jsonPath("$.mobileNumber").value("+919876543210"))
                .andExpect(jsonPath("$.occupation").isEmpty())
                .andExpect(jsonPath("$.email").value(a.email()))
                .andExpect(jsonPath("$.role").value("USER"));
        assertThat(events(a, "PROFILE_UPDATED")).isEqualTo(1);
    }

    @Test
    void rejectsInvalidProfile() throws Exception {
        Account a = register();
        mvc.perform(put("/api/users/me/profile").header("Authorization", "Bearer " + a.token())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"T\",\"mobileNumber\":\"12\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void requiresSignIn() throws Exception {
        mvc.perform(get("/api/users/me/bank-account")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/users/me/profile").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"T\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void bankDetailsNeedTwoFactorToBeOn() throws Exception {
        Account a = register();
        assertThat(saveBank(a, bankBody(NUMBER, PASSWORD, "123456"))).isEqualTo(403);
        assertThat(bankRows(a)).isZero();
    }

    @Test
    void wrongPasswordOrCodeSavesNothingAndIsAudited() throws Exception {
        Account a = register();
        String secret = enrollMfa(a);
        assertThat(saveBank(a, bankBody(NUMBER, "Wrong#1234", code(a, secret)))).isEqualTo(401);
        String right = Totp.code(secret, Totp.step(Instant.now()));
        assertThat(saveBank(a, bankBody(NUMBER, PASSWORD, right.equals("000000") ? "111111" : "000000"))).isEqualTo(401);
        assertThat(bankRows(a)).isZero();
        assertThat(events(a, "BANK_DETAILS_CHANGE_FAILED")).isEqualTo(2);
    }

    @Test
    void storesTheNumberEncryptedAndOnlyEverShowsTheLastFour() throws Exception {
        Account a = register();
        String secret = enrollMfa(a);
        assertThat(saveBank(a, bankBody(NUMBER, PASSWORD, code(a, secret)))).isEqualTo(200);

        String stored = jdbc.queryForObject("select account_number_ciphertext from user_bank_accounts where user_id = ?",
                String.class, a.id());
        assertThat(stored).doesNotContain(NUMBER);
        assertThat(encryptor.decrypt(stored, "bank-account:user:" + a.id())).isEqualTo(NUMBER);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> encryptor.decrypt(stored, "bank-account:user:" + (a.id() + 1))).isInstanceOf(RuntimeException.class);

        String response = mvc.perform(get("/api/users/me/bank-account").header("Authorization", "Bearer " + a.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.account.maskedAccountNumber").value("••••••9012"))
                .andExpect(jsonPath("$.account.ifsc").value("HDFC0001234"))
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain(NUMBER).doesNotContain(stored);

        String detail = jdbc.queryForObject("select detail from security_events where user_id = ? and type = 'BANK_DETAILS_SAVED'",
                String.class, a.id());
        assertThat(detail).isEqualTo("ending 9012");
    }

    @Test
    void oneUserCanNeverSeeAnothersAccount() throws Exception {
        Account owner = register();
        String secret = enrollMfa(owner);
        assertThat(saveBank(owner, bankBody(NUMBER, PASSWORD, code(owner, secret)))).isEqualTo(200);
        Account other = register();
        mvc.perform(get("/api/users/me/bank-account").header("Authorization", "Bearer " + other.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.account").doesNotExist());
    }

    @Test
    void replacingKeepsOneAccountAndRemovingDeletesIt() throws Exception {
        Account a = register();
        String secret = enrollMfa(a);
        assertThat(saveBank(a, bankBody(NUMBER, PASSWORD, code(a, secret)))).isEqualTo(200);
        assertThat(saveBank(a, bankBody("999988887777", PASSWORD, code(a, secret)))).isEqualTo(200);
        assertThat(bankRows(a)).isEqualTo(1);

        mvc.perform(post("/api/users/me/bank-account/remove").header("Authorization", "Bearer " + a.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"" + PASSWORD + "\",\"code\":\"" + code(a, secret) + "\"}"))
                .andExpect(status().isNoContent());
        assertThat(bankRows(a)).isZero();
        mvc.perform(get("/api/users/me/bank-account").header("Authorization", "Bearer " + a.token()))
                .andExpect(jsonPath("$.account").isEmpty());
    }

    @Test
    void refusesAnInvalidAccountNumber() throws Exception {
        Account a = register();
        String secret = enrollMfa(a);
        assertThat(saveBank(a, bankBody("12AB", PASSWORD, code(a, secret)))).isEqualTo(400);
        assertThat(bankRows(a)).isZero();
    }
}
