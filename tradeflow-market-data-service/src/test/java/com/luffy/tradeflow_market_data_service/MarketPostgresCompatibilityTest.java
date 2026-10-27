package com.luffy.tradeflow_market_data_service;

import com.luffy.tradeflow.common.testsupport.PostgresTestDatabase;
import org.junit.jupiter.api.Tag;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import com.luffy.tradeflow_market_data_service.service.MarketSimulationService;
import com.luffy.tradeflow_market_data_service.service.QuoteService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Seeding, SELECT ... FOR UPDATE tick generation and quote queries on real PostgreSQL 16. */
@Tag("postgres")
@SpringBootTest
@ActiveProfiles("test")
class MarketPostgresCompatibilityTest {

    private static final String DATABASE = "tradeflow_market_data_it";

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        PostgresTestDatabase db = PostgresTestDatabase.get();
        registry.add("spring.datasource.url", () -> db.createDatabase(DATABASE));
        registry.add("spring.datasource.username", db::username);
        registry.add("spring.datasource.password", db::password);
    }

    @Autowired MarketSimulationService simulation;
    @Autowired QuoteService quotes;

    @Test
    void seedsAndAdvancesOnPostgres() {
        long tick = quotes.currentTick();
        assertThat(tick).isEqualTo(89);
        assertThat(simulation.advance(3)).isEqualTo(tick + 3);
        assertThat(quotes.quotes(List.of("TCS", "INFY"))).hasSize(2)
                .allSatisfy(q -> assertThat(q.tick()).isEqualTo(tick + 3));
        assertThat(quotes.history("TCS", null, 1000)).hasSize(93);
    }
}
