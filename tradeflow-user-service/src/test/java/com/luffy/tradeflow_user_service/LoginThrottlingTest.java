package com.luffy.tradeflow_user_service;

import com.luffy.tradeflow.common.web.ApiException;
import com.luffy.tradeflow_user_service.service.LoginAttemptService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoginThrottlingTest {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
    private final Clock clock = new Clock() {
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    };
    private final LoginAttemptService service = new LoginAttemptService(3, 5, Duration.ofMinutes(15), clock);

    @Test
    void locksAccountAfterLimitAndUnlocksWhenWindowPasses() {
        for (int i = 0; i < 3; i++) {
            service.recordFailure("a@test.dev", "10.0.0.1");
        }
        assertThatThrownBy(() -> service.checkAllowed("a@test.dev", "10.0.0.2"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    org.assertj.core.api.Assertions.assertThat(e.getStatus().value()).isEqualTo(429);
                    org.assertj.core.api.Assertions.assertThat(e.getRetryAfterSeconds()).isEqualTo(900);
                });
        assertThatCode(() -> service.checkAllowed("other@test.dev", "10.0.0.2")).doesNotThrowAnyException();
        now.set(now.get().plus(Duration.ofMinutes(15)).plusSeconds(1));
        assertThatCode(() -> service.checkAllowed("a@test.dev", "10.0.0.2")).doesNotThrowAnyException();
    }

    @Test
    void capsFailuresPerClientAcrossAccounts() {
        for (int i = 0; i < 5; i++) {
            service.recordFailure("user" + i + "@test.dev", "10.0.0.9");
        }
        assertThatThrownBy(() -> service.checkAllowed("fresh@test.dev", "10.0.0.9")).isInstanceOf(ApiException.class);
        assertThatCode(() -> service.checkAllowed("fresh@test.dev", "10.0.0.10")).doesNotThrowAnyException();
    }

    @Test
    void successClearsTheAccountCounter() {
        service.recordFailure("b@test.dev", "10.0.0.1");
        service.recordFailure("b@test.dev", "10.0.0.1");
        service.recordSuccess("b@test.dev");
        service.recordFailure("b@test.dev", "10.0.0.1");
        assertThatCode(() -> service.checkAllowed("b@test.dev", "10.0.0.1")).doesNotThrowAnyException();
    }
}
