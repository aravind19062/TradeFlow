package com.luffy.tradeflow_learning_service;

import com.luffy.tradeflow.common.testsupport.PostgresTestDatabase;
import com.luffy.tradeflow_learning_service.academy.AcademyService;
import com.luffy.tradeflow_learning_service.readiness.ReadinessService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Migrations (incl. the PostgreSQL-only append-only trigger) and readiness on real PostgreSQL 16. */
@Tag("postgres")
@SpringBootTest
@ActiveProfiles("test")
class LearningPostgresCompatibilityTest {

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        PostgresTestDatabase db = PostgresTestDatabase.get();
        registry.add("spring.datasource.url", () -> db.createDatabase("tradeflow_learning_it"));
        registry.add("spring.datasource.username", db::username);
        registry.add("spring.datasource.password", db::password);
    }

    @Autowired AcademyService academy;
    @Autowired ReadinessService readiness;
    @Autowired JdbcTemplate jdbc;

    @Test
    void readinessAuditLogIsAppendOnlyOnPostgres() {
        academy.completeLesson(42, "stock-market-fundamentals", "what-is-a-stock");
        readiness.reconcile(42);
        assertThat(readiness.verifyChain(42).intact()).isTrue();
        assertThat(readiness.verifyChain(42).events()).isEqualTo(2);
        assertThatThrownBy(() -> jdbc.update("update readiness_events set reason_code = 'FORGED' where user_id = 42"))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("delete from readiness_events where user_id = 42"))
                .hasMessageContaining("append-only");
    }
}
