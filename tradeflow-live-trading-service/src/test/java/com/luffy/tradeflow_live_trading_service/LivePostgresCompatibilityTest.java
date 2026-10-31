package com.luffy.tradeflow_live_trading_service;

import com.luffy.tradeflow.common.testsupport.PostgresTestDatabase;
import com.luffy.tradeflow_live_trading_service.audit.LiveAuditService;
import com.luffy.tradeflow_live_trading_service.platform.PlatformChecks;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Migrations (incl. the PostgreSQL-only append-only trigger) on real PostgreSQL 16. */
@Tag("postgres")
@SpringBootTest
@ActiveProfiles("test")
class LivePostgresCompatibilityTest {

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        PostgresTestDatabase db = PostgresTestDatabase.get();
        registry.add("spring.datasource.url", () -> db.createDatabase("tradeflow_live_it"));
        registry.add("spring.datasource.username", db::username);
        registry.add("spring.datasource.password", db::password);
    }

    @Autowired LiveAuditService audit;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean PlatformChecks platform;

    @Test
    void liveAuditLogIsAppendOnlyOnPostgres() {
        audit.record(42, "PLACE_ORDER", LiveAuditService.Outcome.REFUSED, "NOT_ELIGIBLE", 42);
        audit.record(42, "PLACE_ORDER", LiveAuditService.Outcome.REFUSED, "STEP_UP_REQUIRED", 42);
        assertThat(audit.verify(42).intact()).isTrue();
        assertThat(audit.verify(42).events()).isEqualTo(2);
        assertThatThrownBy(() -> jdbc.update("update live_audit_log set detail = 'forged' where user_id = 42"))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("delete from live_audit_log where user_id = 42"))
                .hasMessageContaining("append-only");
    }
}
