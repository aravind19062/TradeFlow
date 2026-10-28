package com.luffy.tradeflow_portfolio_service.assistant;

import com.luffy.tradeflow.common.security.AuthenticatedUser;
import com.luffy.tradeflow_portfolio_service.assistant.AssistantModels.Fact;
import com.luffy.tradeflow_portfolio_service.client.MarketDataClient;
import com.luffy.tradeflow_portfolio_service.client.OrderServiceClient;
import com.luffy.tradeflow_portfolio_service.service.PortfolioQueryService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Tool arguments are validated strictly; nothing is executed on invalid input. */
class AssistantToolboxTest {

    private final PortfolioQueryService query = mock(PortfolioQueryService.class);
    private final MarketDataClient marketData = mock(MarketDataClient.class);
    private final OrderServiceClient orders = mock(OrderServiceClient.class);
    private final AssistantToolbox toolbox = new AssistantToolbox(query, marketData, orders);
    private final AuthenticatedUser user = new AuthenticatedUser(3, "t@test.dev", Set.of("USER"), "tok");

    @Test
    void rejectsUnknownToolsAndUndeclaredOrMissingArguments() {
        assertThat(toolbox.execute(user, "drop_tables", Map.of()).kind()).isEqualTo("ERROR");
        Fact sneaky = toolbox.execute(user, "get_portfolio_summary", Map.of("user_id", 999));
        assertThat(sneaky.kind()).isEqualTo("ERROR");
        assertThat(sneaky.data().toString()).contains("Unexpected argument 'user_id'");
        assertThat(toolbox.execute(user, "get_order", Map.of()).data().toString())
                .contains("Missing required argument 'order_id'");
        assertThat(toolbox.execute(user, "get_order", Map.of("order_id", "not-a-uuid")).kind()).isEqualTo("ERROR");
        assertThat(toolbox.execute(user, "list_recent_orders", Map.of("status", "HACKED")).kind()).isEqualTo("ERROR");
        verifyNoInteractions(query, orders);
    }

    @Test
    void priceAlertsComeFromTheCallersOwnAlertList() {
        when(marketData.alerts("TRIGGERED")).thenReturn(List.of());
        Fact fact = toolbox.execute(user, "get_price_alerts", Map.of("status", "triggered"));
        assertThat(fact.kind()).isEqualTo("RETRIEVED");
        verify(marketData).alerts("TRIGGERED");
        assertThat(toolbox.execute(user, "get_price_alerts", Map.of("status", "EVERYONE")).kind()).isEqualTo("ERROR");
    }

    @Test
    void metricDefinitionsAreFixedReferenceData() {
        Fact fact = toolbox.execute(user, "explain_metric", Map.of("metric", "max_drawdown"));
        assertThat(fact.kind()).isEqualTo("REFERENCE");
        assertThat(fact.data().toString()).contains("peak");
        assertThat(toolbox.execute(user, "explain_metric", Map.of("metric", "alpha")).kind()).isEqualTo("ERROR");
        assertThat(AssistantToolbox.TOOLS).extracting(AssistantToolbox.ToolSpec::name)
                .containsExactlyInAnyOrder("get_portfolio_summary", "get_risk_report", "get_performance",
                        "get_watchlist_quotes", "get_order", "list_recent_orders", "get_price_alerts", "explain_metric");
    }

    @Test
    void noToolAcceptsAUserIdOrAWriteOperation() {
        for (AssistantToolbox.ToolSpec spec : AssistantToolbox.TOOLS) {
            assertThat(spec.properties().keySet()).noneMatch(k -> k.toLowerCase().contains("user"));
            assertThat(spec.name()).doesNotContain("place", "cancel", "create", "update", "delete", "transfer");
        }
    }
}
