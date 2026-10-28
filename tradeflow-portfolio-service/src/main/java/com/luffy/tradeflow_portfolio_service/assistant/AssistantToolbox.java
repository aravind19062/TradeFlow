package com.luffy.tradeflow_portfolio_service.assistant;

import com.luffy.tradeflow.common.security.AuthenticatedUser;
import com.luffy.tradeflow.common.web.ApiException;
import com.luffy.tradeflow_portfolio_service.analytics.PortfolioAnalytics;
import com.luffy.tradeflow_portfolio_service.assistant.AssistantModels.Fact;
import com.luffy.tradeflow_portfolio_service.client.MarketDataClient;
import com.luffy.tradeflow_portfolio_service.client.OrderServiceClient;
import com.luffy.tradeflow_portfolio_service.service.PortfolioQueryService;
import com.luffy.tradeflow_portfolio_service.web.PortfolioViews.PerformanceReport;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The only way the assistant (LLM or deterministic) reads data. Every tool is
 * read-only and runs as the authenticated caller: no tool accepts a user id, and
 * order lookups go through order-service with the caller's own token. There is
 * deliberately no tool that places, changes or cancels anything.
 */
@Component
public class AssistantToolbox {

    public record ToolSpec(String name, String description, Map<String, Object> properties, List<String> required) {
    }

    public static final List<ToolSpec> TOOLS = List.of(
            new ToolSpec("get_portfolio_summary",
                    "The caller's cash, reserved cash, holdings at the latest simulated prices, cost basis, "
                            + "realized and unrealized P&L, total value and total return.", Map.of(), List.of()),
            new ToolSpec("get_risk_report",
                    "Allocation by symbol, sector and asset class; largest position; Herfindahl concentration "
                            + "index; max drawdown; concentration warnings; and the methodology behind each metric.",
                    Map.of(), List.of()),
            new ToolSpec("get_performance",
                    "Portfolio value over the available simulated history: start/end value, change, peak and "
                            + "max drawdown, plus a few sample points.", Map.of(), List.of()),
            new ToolSpec("get_watchlist_quotes",
                    "Latest simulated quotes (price, change since previous tick) for the caller's watchlist.",
                    Map.of(), List.of()),
            new ToolSpec("get_order",
                    "One of the caller's orders with its status history and executions.",
                    Map.of("order_id", Map.of("type", "string", "description", "Order UUID")), List.of("order_id")),
            new ToolSpec("list_recent_orders",
                    "The caller's most recent orders, newest first.",
                    Map.of("status", Map.of("type", "string",
                                    "enum", List.of("PENDING", "OPEN", "FILLED", "REJECTED", "CANCELLED", "EXPIRED"),
                                    "description", "Optional status filter, e.g. OPEN for resting orders"),
                            "limit", Map.of("type", "integer", "minimum", 1, "maximum", 20)),
                    List.of()),
            new ToolSpec("get_price_alerts",
                    "The caller's price alerts (symbol, ABOVE/BELOW threshold, status, trigger price and time).",
                    Map.of("status", Map.of("type", "string", "enum", List.of("ACTIVE", "TRIGGERED", "CANCELLED"),
                            "description", "Optional status filter")),
                    List.of()),
            new ToolSpec("explain_metric",
                    "Fixed TradeFlow definition of a portfolio metric. Use it instead of defining metrics yourself.",
                    Map.of("metric", Map.of("type", "string", "enum", List.copyOf(MetricDefinitions.DEFINITIONS.keySet()))),
                    List.of("metric")));

    private static final Set<String> STATUSES = Set.of("PENDING", "OPEN", "FILLED", "REJECTED", "CANCELLED", "EXPIRED");
    private static final Set<String> ALERT_STATUSES = Set.of("ACTIVE", "TRIGGERED", "CANCELLED");
    private static final Map<String, ToolSpec> BY_NAME = TOOLS.stream()
            .collect(java.util.stream.Collectors.toMap(ToolSpec::name, t -> t));

    private final PortfolioQueryService queryService;
    private final MarketDataClient marketDataClient;
    private final OrderServiceClient orderServiceClient;

    public AssistantToolbox(PortfolioQueryService queryService, MarketDataClient marketDataClient,
                            OrderServiceClient orderServiceClient) {
        this.queryService = queryService;
        this.marketDataClient = marketDataClient;
        this.orderServiceClient = orderServiceClient;
    }

    /** Runs a tool; failures become an ERROR fact the model (or template) can report honestly. */
    public Fact execute(AuthenticatedUser user, String tool, Map<String, Object> input) {
        Map<String, Object> args = input == null ? Map.of() : input;
        ToolSpec spec = BY_NAME.get(tool);
        if (spec == null) {
            return error(tool, args, "Unknown tool " + tool);
        }
        // Reject arguments the tool does not declare, and missing required ones, instead of ignoring them.
        for (String key : args.keySet()) {
            if (!spec.properties().containsKey(key)) {
                return error(tool, args, "Unexpected argument '" + key + "'");
            }
        }
        for (String required : spec.required()) {
            if (args.get(required) == null || String.valueOf(args.get(required)).isBlank()) {
                return error(tool, args, "Missing required argument '" + required + "'");
            }
        }
        try {
            return switch (tool) {
                case "get_portfolio_summary" -> new Fact(tool, args, "CALCULATED", queryService.summary(user.id()));
                case "get_risk_report" -> new Fact(tool, args, "CALCULATED", queryService.risk(user.id()));
                case "get_performance" -> new Fact(tool, args, "CALCULATED",
                        compact(queryService.performance(user.id())));
                case "get_watchlist_quotes" -> new Fact(tool, args, "SIMULATED_MARKET_DATA",
                        marketDataClient.watchlist());
                case "get_order" -> new Fact(tool, args, "RETRIEVED",
                        orderServiceClient.order(UUID.fromString(String.valueOf(args.get("order_id")).trim())));
                case "list_recent_orders" -> new Fact(tool, args, "RETRIEVED",
                        orderServiceClient.recentOrders(status(args.get("status")), limit(args.get("limit"))));
                case "get_price_alerts" -> new Fact(tool, args, "RETRIEVED",
                        marketDataClient.alerts(alertStatus(args.get("status"))));
                case "explain_metric" -> {
                    String metric = String.valueOf(args.get("metric")).trim().toLowerCase();
                    String definition = MetricDefinitions.DEFINITIONS.get(metric);
                    yield definition == null
                            ? error(tool, args, "Unknown metric; expected one of " + MetricDefinitions.DEFINITIONS.keySet())
                            : new Fact(tool, args, "REFERENCE", Map.of("metric", metric, "definition", definition));
                }
                default -> error(tool, args, "Unknown tool " + tool);
            };
        } catch (ApiException e) {
            return error(tool, args, e.getMessage());
        } catch (IllegalArgumentException e) {
            return error(tool, args, "Invalid input: " + e.getMessage());
        }
    }

    public static Fact methodology(String metric) {
        return new Fact("methodology", Map.of("metric", metric), "REFERENCE", PortfolioAnalytics.RISK_METHODOLOGY);
    }

    private static Fact error(String tool, Map<String, Object> args, String message) {
        return new Fact(tool, args, "ERROR", Map.of("error", message));
    }

    /** Sends a few representative points instead of hundreds, to keep model requests small. */
    private static Map<String, Object> compact(PerformanceReport r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("from", r.from());
        m.put("to", r.to());
        m.put("startValue", r.startValue());
        m.put("endValue", r.endValue());
        m.put("change", r.change());
        m.put("changeRatio", r.changeRatio());
        m.put("peakValue", r.peakValue());
        m.put("maxDrawdownRatio", r.maxDrawdownRatio());
        m.put("pointCount", r.pointCount());
        int n = r.points().size();
        m.put("samplePoints", n <= 6 ? r.points()
                : List.of(r.points().get(0), r.points().get(n / 4), r.points().get(n / 2), r.points().get(3 * n / 4),
                r.points().get(n - 1)));
        m.put("notes", r.notes());
        return m;
    }

    private static String status(Object raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.toString().trim().toUpperCase();
        if (!STATUSES.contains(s)) {
            throw new IllegalArgumentException("status must be one of " + STATUSES);
        }
        return s;
    }

    private static String alertStatus(Object raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.toString().trim().toUpperCase();
        if (!ALERT_STATUSES.contains(s)) {
            throw new IllegalArgumentException("status must be one of " + ALERT_STATUSES);
        }
        return s;
    }

    private static int limit(Object raw) {
        if (raw == null) {
            return 10;
        }
        int n = raw instanceof Number num ? num.intValue() : Integer.parseInt(raw.toString().trim());
        return Math.max(1, Math.min(20, n));
    }
}
