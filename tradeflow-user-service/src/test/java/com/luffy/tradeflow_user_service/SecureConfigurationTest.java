package com.luffy.tradeflow_user_service;

import com.luffy.tradeflow.common.security.AuthenticatedUser;
import com.luffy.tradeflow.common.security.JwtProperties;
import com.luffy.tradeflow.common.security.JwtSecurityConfig;
import com.luffy.tradeflow.common.security.ProblemDetailSecurityHandler;
import com.luffy.tradeflow_user_service.config.DemoDataSeeder;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.json.JsonMapper;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unsafe defaults must be impossible outside the explicit local profile. */
class SecureConfigurationTest {

    private static final String PLACEHOLDER = "local-dev-only-insecure-jwt-secret-do-not-use-in-prod";
    private final JwtSecurityConfig config = new JwtSecurityConfig(
            new ProblemDetailSecurityHandler(JsonMapper.builder().build()));

    private static MockEnvironment env(String... profiles) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profiles);
        return env;
    }

    @Test
    void missingOrShortSecretFailsFast() {
        assertThatThrownBy(() -> new JwtProperties(null, null, null)).hasMessageContaining("TRADEFLOW_JWT_SECRET");
        assertThatThrownBy(() -> new JwtProperties("too-short", null, null)).hasMessageContaining("at least 32 bytes");
    }

    @Test
    void placeholderSecretOnlyAllowedInLocalProfile() {
        JwtProperties props = new JwtProperties(PLACEHOLDER, null, null);
        assertThatThrownBy(() -> config.jwtSigningKey(props, env()))
                .hasMessageContaining("outside the 'local' profile");
        assertThatThrownBy(() -> config.jwtSigningKey(props, env("prod")))
                .isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> config.jwtSigningKey(props, env("local"))).doesNotThrowAnyException();
        assertThatCode(() -> config.jwtSigningKey(new JwtProperties("a-private-secret-of-at-least-32-bytes!!", null,
                null), env("prod"))).doesNotThrowAnyException();
    }

    @Test
    void demoAdminSeedingNeedsStrongPrivatePasswordOutsideLocal() {
        assertThatThrownBy(() -> new DemoDataSeeder(null, null, "Demo@12345", env("prod")))
                .hasMessageContaining("Refusing to seed demo users");
        assertThatThrownBy(() -> new DemoDataSeeder(null, null, "", env())).isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> new DemoDataSeeder(null, null, "Demo@12345", env("local"))).doesNotThrowAnyException();
        assertThatCode(() -> new DemoDataSeeder(null, null, "a-long-private-password", env("prod")))
                .doesNotThrowAnyException();
    }

    @Test
    void secretsNeverAppearInToString() {
        assertThat(new JwtProperties(PLACEHOLDER, null, null).toString()).doesNotContain(PLACEHOLDER).contains("redacted");
        assertThat(new AuthenticatedUser(7, "u@test.dev", Set.of("USER"), "eyJ.secret.token").toString())
                .doesNotContain("eyJ.secret.token");
    }
}
