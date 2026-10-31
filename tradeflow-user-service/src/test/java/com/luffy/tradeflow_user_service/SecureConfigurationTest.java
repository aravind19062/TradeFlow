package com.luffy.tradeflow_user_service;

import com.luffy.tradeflow.common.security.AuthenticatedUser;
import com.luffy.tradeflow.common.security.JwtProperties;
import com.luffy.tradeflow.common.security.PublicKeyRing;
import com.luffy.tradeflow_user_service.config.DemoDataSeeder;
import com.luffy.tradeflow_user_service.security.AuthProperties;
import com.luffy.tradeflow_user_service.security.ServiceClientAuthenticator;
import com.luffy.tradeflow_user_service.security.TokenIssuer;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unsafe defaults must be impossible outside the explicit local profile. */
class SecureConfigurationTest {

    private static MockEnvironment env(String... profiles) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profiles);
        return env;
    }

    private static AuthProperties auth(String signingKey, Map<String, String> clients) {
        return new AuthProperties(signingKey, null, null, null, null, null, null, null, null, clients);
    }

    @Test
    void missingKeysFailFast() {
        assertThatThrownBy(() -> PublicKeyRing.load(null)).hasMessageContaining("public-keys is not set");
        assertThatThrownBy(() -> new TokenIssuer(new JwtProperties(null, null, null), auth("", Map.of()), Clock.systemUTC()))
                .hasMessageContaining("signing-key is not set");
        assertThatThrownBy(() -> new TokenIssuer(new JwtProperties(null, null, null),
                auth("classpath:jwt-test/public/" + com.luffy.tradeflow.common.testsupport.TestJwts.KEY_ID + ".pem",
                        Map.of()), Clock.systemUTC()))
                .hasMessageContaining("PKCS#8");
    }

    @Test
    void tokenLifetimesAreBounded() {
        assertThatThrownBy(() -> new AuthProperties("x", Duration.ofHours(8), null, null, null, null, null, null, null, null))
                .hasMessageContaining("at most 1 hour");
        assertThatThrownBy(() -> new AuthProperties("x", null, Duration.ofHours(1), null, null, null, null, null, null, null))
                .hasMessageContaining("at most 15 minutes");
    }

    @Test
    void placeholderClientSecretsOnlyInLocalProfile() {
        Map<String, String> placeholder = Map.of("tradeflow-portfolio-service", "local-dev-only-portfolio-client-secret");
        assertThatThrownBy(() -> new ServiceClientAuthenticator(auth("x", placeholder), env("prod")))
                .hasMessageContaining("outside the 'local' profile");
        assertThatCode(() -> new ServiceClientAuthenticator(auth("x", placeholder), env("local"))).doesNotThrowAnyException();
        assertThatThrownBy(() -> new ServiceClientAuthenticator(auth("x", Map.of("svc", "short")), env("prod")))
                .hasMessageContaining("at least 24 characters");
        // Blank = client not configured on this deployment.
        ServiceClientAuthenticator none = new ServiceClientAuthenticator(auth("x", Map.of("svc", "")), env("prod"));
        assertThat(none.authenticate("svc", "")).isFalse();
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
        String secret = "a-private-client-secret-0123456789";
        assertThat(auth("x", Map.of("svc", secret)).toString()).doesNotContain(secret).contains("svc");
        assertThat(new AuthenticatedUser(7, "u@test.dev", Set.of("USER"), "eyJ.secret.token").toString())
                .doesNotContain("eyJ.secret.token");
    }
}
