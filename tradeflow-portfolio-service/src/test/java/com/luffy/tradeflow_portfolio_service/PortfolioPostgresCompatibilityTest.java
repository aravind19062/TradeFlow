package com.luffy.tradeflow_portfolio_service;

import com.luffy.tradeflow.common.testsupport.PostgresTestDatabase;
import org.junit.jupiter.api.Tag;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import com.luffy.tradeflow.common.events.EventCodec;
import com.luffy.tradeflow.common.events.Events;
import com.luffy.tradeflow.common.events.Topics;
import com.luffy.tradeflow_portfolio_service.messaging.PortfolioEventListener;
import com.luffy.tradeflow_portfolio_service.repository.AccountRepository;
import com.luffy.tradeflow_portfolio_service.service.ReconciliationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Accounting flow, NUMERIC precision and CHECK constraints on real PostgreSQL 16. */
@Tag("postgres")
@SpringBootTest
@ActiveProfiles("test")
class PortfolioPostgresCompatibilityTest {

    private static final String DATABASE = "tradeflow_portfolio_it";
    private static final long USER = 9;

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        PostgresTestDatabase db = PostgresTestDatabase.get();
        registry.add("spring.datasource.url", () -> db.createDatabase(DATABASE));
        registry.add("spring.datasource.username", db::username);
        registry.add("spring.datasource.password", db::password);
    }

    @Autowired PortfolioEventListener listener;
    @Autowired EventCodec codec;
    @Autowired AccountRepository accounts;
    @Autowired ReconciliationService reconciliation;
    @Autowired JdbcTemplate jdbc;

    private String encode(String topic, Object payload) {
        return codec.encode(codec.wrap(topic, String.valueOf(USER), payload));
    }

    @Test
    void settlesTradesAndEnforcesConstraintsOnPostgres() {
        listener.onUserRegistered(encode(Topics.USERS_REGISTERED, new Events.UserRegistered(USER, "p@test.dev", "P")));
        UUID orderId = UUID.randomUUID();
        listener.onOrderPlaced(encode(Topics.ORDERS_PLACED, new Events.OrderPlaced(orderId, USER, "TCS",
                Events.OrderSide.BUY, Events.OrderType.MARKET, 3, new BigDecimal("333.33"))));
        listener.onTradeExecuted(encode(Topics.TRADES_EXECUTED, new Events.TradeExecuted(UUID.randomUUID(), orderId,
                USER, "TCS", Events.OrderSide.BUY, 3, new BigDecimal("333.33"), new BigDecimal("999.99"),
                Instant.now())));

        assertThat(accounts.findById(USER).orElseThrow().getCashBalance()).isEqualByComparingTo("999000.01");
        assertThat(reconciliation.check(USER).consistent()).isTrue();

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE accounts SET reserved_cash = cash_balance + 1 WHERE user_id = ?", USER))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE holdings SET reserved_quantity = 99 WHERE user_id = ?", USER))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
