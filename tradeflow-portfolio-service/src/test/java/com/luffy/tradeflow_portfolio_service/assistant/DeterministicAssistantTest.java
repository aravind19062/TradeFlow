package com.luffy.tradeflow_portfolio_service.assistant;

import com.luffy.tradeflow.common.security.AuthenticatedUser;
import com.luffy.tradeflow_portfolio_service.analytics.PortfolioAnalytics;
import com.luffy.tradeflow_portfolio_service.assistant.AssistantModels.Fact;
import com.luffy.tradeflow_portfolio_service.assistant.DeterministicAssistant.Intent;
import com.luffy.tradeflow_portfolio_service.domain.Account;
import com.luffy.tradeflow_portfolio_service.domain.Holding;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DeterministicAssistantTest {

    private final DeterministicAssistant assistant = new DeterministicAssistant();
    private final AuthenticatedUser user = new AuthenticatedUser(1, "u@test.dev", Set.of("USER"), "t");

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "What is my current portfolio worth?|SUMMARY",
            "Which holdings have the highest concentration?|RISK",
            "What are my realized and unrealized gains?|PNL",
            "How has my portfolio changed over the available history?|PERFORMANCE",
            "Summarize the latest market data for my watchlist|WATCHLIST",
            "Explain the risks and assumptions behind max drawdown|EXPLAIN",
            "Why was order 3fbd06b5-3a5e-4d11-9c60-91a785a2b6a7 rejected?|ORDER",
            "Show my recent orders|ORDER",
            "Buy 10 TCS for me|ACTION_REQUEST",
            "Sell everything now|ACTION_REQUEST",
            "Cancel order 3fbd06b5-3a5e-4d11-9c60-91a785a2b6a7|ACTION_REQUEST",
            "Which of my price alerts have triggered?|ALERTS",
            "What are my open orders?|OPEN_ORDERS",
            "What is my average cost for TCS?|HOLDINGS",
            "What do I hold?|HOLDINGS",
            "Explain average cost|EXPLAIN",
            "Tell me a joke|HELP"})
    void classifiesQuestions(String question, Intent expected) {
        assertThat(assistant.classify(question)).isEqualTo(expected);
    }

    @Test
    void summaryIsBuiltOnlyFromToolFacts() {
        Account account = Account.open(1, new BigDecimal("10000.00"), Instant.now());
        account.reserve(new BigDecimal("1000.00"), Instant.now());
        account.settleBuy(new BigDecimal("1000.00"), new BigDecimal("1000.00"), Instant.now());
        Holding tcs = new Holding(1, "TCS", Instant.now());
        tcs.applyBuy(10, new BigDecimal("1000.00"), Instant.now());
        var quote = new com.luffy.tradeflow_portfolio_service.client.MarketDataClient.Quote("TCS", "TCS", "IT",
                "EQUITY", "INR", new BigDecimal("120.00"), new BigDecimal("120.00"), BigDecimal.ZERO, BigDecimal.ZERO,
                1, Instant.now(), "SIMULATED");
        var summary = PortfolioAnalytics.summarize(account, List.of(tcs), Map.of("TCS", quote), true, "INR");
        AssistantToolbox toolbox = mock(AssistantToolbox.class);
        when(toolbox.execute(eq(user), eq("get_portfolio_summary"), any()))
                .thenReturn(new Fact("get_portfolio_summary", Map.of(), "CALCULATED", summary));

        var result = assistant.answer("What is my portfolio worth?", Intent.SUMMARY, user, toolbox);

        assertThat(result.answer()).contains("₹10,200.00").contains("₹9,000.00").contains("₹1,200.00")
                .contains("+₹200.00").contains("TCS 10 @ ₹120.00");
        assertThat(result.facts()).extracting(Fact::tool).containsExactly("get_portfolio_summary");
    }

    @ParameterizedTest
    @CsvSource({"0,₹0.00", "999.5,₹999.50", "1000,'₹1,000.00'", "100000,'₹1,00,000.00'",
            "1234567.891,'₹12,34,567.89'", "-25000,'-₹25,000.00'"})
    void formatsRupeesWithIndianGrouping(String amount, String expected) {
        assertThat(DeterministicAssistant.inr(new BigDecimal(amount))).isEqualTo(expected);
    }

    @Test
    void reportsToolErrorsInsteadOfInventingData() {
        AssistantToolbox toolbox = mock(AssistantToolbox.class);
        when(toolbox.execute(eq(user), eq("get_order"), any())).thenReturn(new Fact("get_order",
                Map.of(), "ERROR", Map.of("error", "Order 3fbd06b5-3a5e-4d11-9c60-91a785a2b6a7 not found")));
        var result = assistant.answer("status of order 3fbd06b5-3a5e-4d11-9c60-91a785a2b6a7", Intent.ORDER, user,
                toolbox);
        assertThat(result.answer()).startsWith("I couldn't retrieve that data").contains("only visible to the user");
    }

    @Test
    void actionRequestsNeverTouchData() {
        AssistantToolbox toolbox = mock(AssistantToolbox.class);
        var result = assistant.answer("Buy 10 TCS", Intent.ACTION_REQUEST, user, toolbox);
        assertThat(result.answer()).isEqualTo(DeterministicAssistant.ACTION_REFUSAL);
        verifyNoInteractions(toolbox);
    }
}
