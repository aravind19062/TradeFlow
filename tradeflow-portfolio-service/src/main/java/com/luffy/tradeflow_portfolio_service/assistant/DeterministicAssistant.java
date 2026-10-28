package com.luffy.tradeflow_portfolio_service.assistant;

import com.luffy.tradeflow.common.security.AuthenticatedUser;
import com.luffy.tradeflow_portfolio_service.analytics.PortfolioAnalytics;
import com.luffy.tradeflow_portfolio_service.assistant.AssistantModels.Fact;
import com.luffy.tradeflow_portfolio_service.client.MarketDataClient.Quote;
import com.luffy.tradeflow_portfolio_service.web.PortfolioViews.AllocationSlice;
import com.luffy.tradeflow_portfolio_service.web.PortfolioViews.HoldingView;
import com.luffy.tradeflow_portfolio_service.web.PortfolioViews.PortfolioSummary;
import com.luffy.tradeflow_portfolio_service.web.PortfolioViews.RiskReport;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keyword routing plus templates. Used when no LLM is configured or when the LLM
 * fails. Every sentence is built from tool results; nothing is generated.
 */
@Component
public class DeterministicAssistant {

    public enum Intent { ACTION_REQUEST, ORDER, OPEN_ORDERS, ALERTS, WATCHLIST, RISK, PERFORMANCE, PNL, HOLDINGS,
        EXPLAIN, SUMMARY, HELP }

    private static final Pattern UUID_PATTERN =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern ACTION = Pattern.compile(
            "\\b(buy|sell|purchase|short|place|cancel|execute|transfer|withdraw|deposit)\\b.*"
                    + "|\\b(make|do) (a |the )?trade\\b.*", Pattern.CASE_INSENSITIVE);
    private static final Pattern QUESTION_ABOUT_ORDERS = Pattern.compile(
            "\\b(status|why|what happened|explain|show|list|history|when)\\b", Pattern.CASE_INSENSITIVE);

    public static final String HELP_TEXT = """
            I can answer questions about your TradeFlow account, for example:
            • What is my portfolio worth?
            • Which holdings have the highest concentration?
            • What are my realized and unrealized gains?
            • How has my portfolio changed over the available history?
            • Summarize the latest market data for my watchlist.
            • Explain how max drawdown (or concentration, HHI, average cost, unrealized P&L) is calculated.
            • What are my holdings and their average cost?
            • Which of my price alerts have triggered? What are my open orders?
            • What is the status of order <order id>?""";

    public static final String ACTION_REFUSAL = "I can't place, change or cancel orders or move money: the "
            + "assistant is read-only by design. Use the Orders API (POST /api/orders, "
            + "POST /api/orders/{id}/cancel) to trade. I can explain your portfolio, risk metrics or an "
            + "order's status.";

    public Intent classify(String question) {
        String q = question.toLowerCase(Locale.ROOT);
        boolean mentionsOrder = UUID_PATTERN.matcher(question).find() || q.contains("order");
        if (ACTION.matcher(q).find() && !(mentionsOrder && QUESTION_ABOUT_ORDERS.matcher(q).find())
                && !q.matches(".*\\b(what|how|which|explain|why)\\b.*")) {
            return Intent.ACTION_REQUEST;
        }
        if (q.matches(".*\\b(open|resting|pending|working)\\b.*\\borders?\\b.*") && !UUID_PATTERN.matcher(question).find()) {
            return Intent.OPEN_ORDERS;
        }
        if (mentionsOrder) {
            return Intent.ORDER;
        }
        if (q.matches(".*\\balerts?\\b.*")) {
            return Intent.ALERTS;
        }
        if (q.contains("watchlist") || q.contains("watch list")) {
            return Intent.WATCHLIST;
        }
        if (q.matches(".*\\b(explain|methodology|assumption|assumptions|how is|how are|calculated|definition|mean)\\b.*")) {
            return Intent.EXPLAIN;
        }
        if (q.matches(".*\\b(concentrat\\w*|risk\\w*|allocation|diversif\\w*|sector\\w*|exposure|hhi|weight\\w*)\\b.*")) {
            return Intent.RISK;
        }
        if (q.matches(".*\\b(history|over time|performance|changed|change|trend|drawdown|return)\\b.*")) {
            return Intent.PERFORMANCE;
        }
        if (q.matches(".*\\b(realized|unrealized|realised|unrealised|gain\\w*|loss\\w*|p&l|pnl|profit\\w*)\\b.*")) {
            return Intent.PNL;
        }
        if (q.matches(".*\\b(average cost|avg cost|cost basis|holdings?|positions?|what do i (own|hold))\\b.*")) {
            return Intent.HOLDINGS;
        }
        if (q.matches(".*\\b(worth|value|balance|cash|summary|portfolio|holding\\w*|position\\w*)\\b.*")) {
            return Intent.SUMMARY;
        }
        return Intent.HELP;
    }

    public record Result(String answer, List<Fact> facts) {
    }

    public Result answer(String question, Intent intent, AuthenticatedUser user, AssistantToolbox toolbox) {
        List<Fact> facts = new ArrayList<>();
        java.util.function.Function<String, Fact> run = tool -> {
            Fact f = toolbox.execute(user, tool, Map.of());
            facts.add(f);
            return f;
        };
        String text = switch (intent) {
            case ACTION_REQUEST -> ACTION_REFUSAL;
            case HELP -> HELP_TEXT;
            case SUMMARY -> summary(run.apply("get_portfolio_summary"));
            case PNL -> pnl(run.apply("get_portfolio_summary"));
            case HOLDINGS -> holdings(run.apply("get_portfolio_summary"));
            case ALERTS -> {
                Fact f = toolbox.execute(user, "get_price_alerts", Map.of());
                facts.add(f);
                yield alerts(f);
            }
            case OPEN_ORDERS -> {
                Fact f = toolbox.execute(user, "list_recent_orders", Map.of("status", "OPEN", "limit", 20));
                facts.add(f);
                yield recentOrders(f).replace("Your most recent orders", "Your open (resting) orders");
            }
            case RISK -> risk(run.apply("get_risk_report"));
            case PERFORMANCE -> performance(run.apply("get_performance"));
            case WATCHLIST -> watchlist(run.apply("get_watchlist_quotes"));
            case EXPLAIN -> {
                String metric = metricFor(question);
                if (metric == null) {
                    Fact f = AssistantToolbox.methodology(question);
                    facts.add(f);
                    yield "TradeFlow's risk metrics: " + String.join(" ", PortfolioAnalytics.RISK_METHODOLOGY);
                }
                Fact f = toolbox.execute(user, "explain_metric", Map.of("metric", metric));
                facts.add(f);
                yield MetricDefinitions.DEFINITIONS.get(metric);
            }
            case ORDER -> {
                Matcher m = UUID_PATTERN.matcher(question);
                if (m.find()) {
                    Fact f = toolbox.execute(user, "get_order", Map.of("order_id", m.group()));
                    facts.add(f);
                    yield order(f);
                }
                Fact f = toolbox.execute(user, "list_recent_orders", Map.of("limit", 5));
                facts.add(f);
                yield recentOrders(f);
            }
        };
        return new Result(text, facts);
    }

    // ------------------------------------------------------------------ templates

    private static String summary(Fact f) {
        if (isError(f)) {
            return unavailable(f);
        }
        PortfolioSummary s = (PortfolioSummary) f.data();
        StringBuilder b = new StringBuilder();
        b.append("Your portfolio is worth ").append(inr(s.totalValue())).append(": cash ").append(inr(s.cashBalance()))
                .append(" (").append(inr(s.reservedCash())).append(" reserved for open orders) and holdings worth ")
                .append(inr(s.holdingsMarketValue())).append(" at the latest simulated prices.");
        b.append(" Total P&L since your initial deposit of ").append(inr(s.netDeposits())).append(" is ")
                .append(signed(s.totalPnl())).append(" (").append(PortfolioAnalytics.percent(s.totalReturnRatio()))
                .append(").");
        if (!s.holdings().isEmpty()) {
            b.append(" Holdings: ");
            List<String> parts = new ArrayList<>();
            for (HoldingView h : s.holdings()) {
                parts.add(h.symbol() + " " + h.quantity() + " @ " + inr(h.marketPrice()));
            }
            b.append(String.join(", ", parts)).append('.');
        }
        if (!s.pricesAvailable()) {
            b.append(" Market data was unavailable, so holdings are valued at cost.");
        }
        return b.toString();
    }

    private static String pnl(Fact f) {
        if (isError(f)) {
            return unavailable(f);
        }
        PortfolioSummary s = (PortfolioSummary) f.data();
        StringBuilder b = new StringBuilder("Realized P&L (from completed sells, against weighted-average cost): ")
                .append(signed(s.realizedPnl())).append(". Unrealized P&L (open holdings at the latest simulated "
                        + "prices minus cost basis): ").append(signed(s.unrealizedPnl())).append('.');
        for (HoldingView h : s.holdings()) {
            b.append("\n• ").append(h.symbol()).append(": unrealized ").append(signed(h.unrealizedPnl()))
                    .append(" (").append(PortfolioAnalytics.percent(h.unrealizedPnlRatio())).append("), realized ")
                    .append(signed(h.realizedPnl()));
        }
        return b.toString();
    }

    private static String risk(Fact f) {
        if (isError(f)) {
            return unavailable(f);
        }
        RiskReport r = (RiskReport) f.data();
        if (r.largestPosition() == null) {
            return "You hold no positions yet, so the portfolio is 100% cash and has no concentration risk.";
        }
        StringBuilder b = new StringBuilder("Your largest position is ").append(r.largestPosition()).append(" at ")
                .append(PortfolioAnalytics.percent(r.largestPositionWeight())).append(" of total value. Cash is ")
                .append(PortfolioAnalytics.percent(r.cashWeight())).append(". Concentration (HHI over holdings) is ")
                .append(r.herfindahlIndex().setScale(3, java.math.RoundingMode.HALF_EVEN))
                .append(", equivalent to about ").append(r.effectiveNumberOfHoldings())
                .append(" equally weighted positions.");
        if (!r.bySector().isEmpty()) {
            AllocationSlice top = r.bySector().get(0);
            b.append(" Largest sector: ").append(top.name()).append(" (")
                    .append(PortfolioAnalytics.percent(top.weight())).append(").");
        }
        if (r.maxDrawdownRatio() != null) {
            b.append(" Max drawdown over the available simulated history: ")
                    .append(PortfolioAnalytics.percent(r.maxDrawdownRatio())).append('.');
        }
        b.append(r.warnings().isEmpty() ? " No concentration warnings." : " Warnings: " + String.join("; ", r.warnings()) + ".");
        return b.toString();
    }

    @SuppressWarnings("unchecked")
    private static String performance(Fact f) {
        if (isError(f)) {
            return unavailable(f);
        }
        Map<String, Object> p = (Map<String, Object>) f.data();
        int points = ((Number) p.get("pointCount")).intValue();
        if (points < 2) {
            return "There isn't enough history yet: the portfolio has " + points + " valuation point(s).";
        }
        return "Over the available simulated history (" + p.get("from") + " to " + p.get("to") + ", " + points
                + " valuation points) your portfolio went from " + inr((BigDecimal) p.get("startValue")) + " to "
                + inr((BigDecimal) p.get("endValue")) + ", a change of " + signed((BigDecimal) p.get("change")) + " ("
                + PortfolioAnalytics.percent((BigDecimal) p.get("changeRatio")) + "). Peak value was "
                + inr((BigDecimal) p.get("peakValue")) + " and the max drawdown was "
                + PortfolioAnalytics.percent((BigDecimal) p.get("maxDrawdownRatio")) + ".";
    }

    @SuppressWarnings("unchecked")
    private static String watchlist(Fact f) {
        if (isError(f)) {
            return unavailable(f);
        }
        List<Quote> quotes = (List<Quote>) f.data();
        if (quotes.isEmpty()) {
            return "Your watchlist is empty. Add symbols with PUT /api/watchlist/{symbol} on market-data-service.";
        }
        StringBuilder b = new StringBuilder("Latest simulated quotes for your watchlist:");
        Quote best = quotes.get(0);
        Quote worst = quotes.get(0);
        for (Quote q : quotes) {
            b.append("\n• ").append(q.symbol()).append(" ").append(inr(q.price())).append(" (")
                    .append(signed(q.change())).append(", ").append(PortfolioAnalytics.percent(q.changeRatio()))
                    .append(" vs previous tick)");
            if (q.changeRatio().compareTo(best.changeRatio()) > 0) {
                best = q;
            }
            if (q.changeRatio().compareTo(worst.changeRatio()) < 0) {
                worst = q;
            }
        }
        if (quotes.size() > 1) {
            b.append("\nBiggest mover up: ").append(best.symbol()).append("; down: ").append(worst.symbol()).append('.');
        }
        return b.toString();
    }

    /** Maps a question to one of the fixed metric definitions, or null. */
    static String metricFor(String question) {
        String q = question.toLowerCase(Locale.ROOT);
        if (q.contains("drawdown")) return "max_drawdown";
        if (q.contains("hhi") || q.contains("herfindahl") || q.contains("concentration")) return "concentration";
        if (q.contains("unrealized") || q.contains("unrealised")) return "unrealized_pnl";
        if (q.contains("realized") || q.contains("realised")) return "realized_pnl";
        if (q.contains("average cost") || q.contains("avg cost") || q.contains("cost basis")) return "average_cost";
        if (q.contains("allocation") || q.contains("weight")) return "allocation";
        if (q.contains("return")) return "total_return";
        if (q.contains("reserved")) return "reserved_cash";
        return null;
    }

    private static String holdings(Fact f) {
        if (isError(f)) {
            return unavailable(f);
        }
        PortfolioSummary s = (PortfolioSummary) f.data();
        if (s.holdings().isEmpty()) {
            return "You hold no positions; your cash balance is " + inr(s.cashBalance()) + ".";
        }
        StringBuilder b = new StringBuilder("Your holdings (average cost uses the weighted-average method):");
        for (HoldingView h : s.holdings()) {
            b.append("\n• ").append(h.symbol()).append(": ").append(h.quantity()).append(" shares");
            if (h.reservedQuantity() > 0) {
                b.append(" (").append(h.reservedQuantity()).append(" reserved for open sell orders)");
            }
            b.append(", average cost ").append(inr(h.averageCost())).append(", latest simulated price ")
                    .append(inr(h.marketPrice())).append(", value ").append(inr(h.marketValue()))
                    .append(" (").append(PortfolioAnalytics.percent(h.weight())).append(" of the portfolio)");
        }
        return b.toString();
    }

    @SuppressWarnings("unchecked")
    private static String alerts(Fact f) {
        if (isError(f)) {
            return unavailable(f);
        }
        List<com.luffy.tradeflow_portfolio_service.client.MarketDataClient.Alert> alerts =
                (List<com.luffy.tradeflow_portfolio_service.client.MarketDataClient.Alert>) f.data();
        if (alerts.isEmpty()) {
            return "You have no price alerts. Create one with POST /api/alerts.";
        }
        StringBuilder b = new StringBuilder("Your price alerts (evaluated on every simulated price tick):");
        for (var a : alerts) {
            b.append("\n• ").append(a.symbol()).append(' ').append(a.direction()).append(' ')
                    .append(inr(a.threshold())).append(" — ").append(a.status());
            if (a.triggeredPrice() != null) {
                b.append(" at ").append(inr(a.triggeredPrice())).append(" (").append(a.triggeredAt()).append(')');
            }
        }
        return b.toString();
    }

    private static String order(Fact f) {
        if (isError(f)) {
            return unavailable(f) + " Order ids are only visible to the user who placed them.";
        }
        JsonNode d = (JsonNode) f.data();
        JsonNode o = d.get("order");
        StringBuilder b = new StringBuilder("Order ").append(o.get("id").asString()).append(" (")
                .append(o.get("type").asString()).append(' ').append(o.get("side").asString()).append(' ')
                .append(o.get("quantity").asLong()).append(' ').append(o.get("symbol").asString()).append(") is ")
                .append(o.get("status").asString()).append('.');
        if (o.hasNonNull("statusReason")) {
            b.append(" Reason: ").append(o.get("statusReason").asString()).append('.');
        }
        b.append(" History:");
        for (JsonNode h : d.get("history")) {
            b.append("\n• ").append(h.get("at").asString()).append(": ")
                    .append(h.hasNonNull("from") ? h.get("from").asString() + " → " : "")
                    .append(h.get("to").asString())
                    .append(h.hasNonNull("reason") ? " (" + h.get("reason").asString() + ")" : "");
        }
        for (JsonNode t : d.get("trades")) {
            b.append("\nExecuted ").append(t.get("quantity").asLong()).append(" @ ")
                    .append(inr(t.get("price").decimalValue())).append(" = ")
                    .append(inr(t.get("grossAmount").decimalValue())).append(" at ").append(t.get("executedAt").asString());
        }
        return b.toString();
    }

    private static String recentOrders(Fact f) {
        if (isError(f)) {
            return unavailable(f);
        }
        JsonNode content = ((JsonNode) f.data()).get("content");
        if (content == null || content.isEmpty()) {
            return "You haven't placed any orders yet.";
        }
        StringBuilder b = new StringBuilder("Your most recent orders (ask about a specific order id for its full "
                + "history):");
        for (JsonNode o : content) {
            b.append("\n• ").append(o.get("id").asString()).append(": ").append(o.get("side").asString()).append(' ')
                    .append(o.get("quantity").asLong()).append(' ').append(o.get("symbol").asString()).append(" — ")
                    .append(o.get("status").asString());
        }
        return b.toString();
    }

    private static boolean isError(Fact f) {
        return "ERROR".equals(f.kind());
    }

    @SuppressWarnings("unchecked")
    private static String unavailable(Fact f) {
        return "I couldn't retrieve that data: " + ((Map<String, Object>) f.data()).get("error") + ".";
    }

    /** Indian digit grouping (₹12,34,567.89); java.text formats only fixed-size groups. */
    static String inr(BigDecimal amount) {
        BigDecimal rounded = amount.setScale(2, java.math.RoundingMode.HALF_EVEN);
        String plain = rounded.abs().toPlainString();
        String whole = plain.substring(0, plain.indexOf('.'));
        String fraction = plain.substring(plain.indexOf('.'));
        StringBuilder grouped = new StringBuilder();
        int len = whole.length();
        for (int i = 0; i < len; i++) {
            grouped.append(whole.charAt(i));
            int remaining = len - 1 - i;
            if (remaining > 0 && (remaining == 3 || (remaining > 3 && (remaining - 3) % 2 == 0))) {
                grouped.append(',');
            }
        }
        return (rounded.signum() < 0 ? "-" : "") + "₹" + grouped + fraction;
    }

    static String signed(BigDecimal amount) {
        return (amount.signum() > 0 ? "+" : "") + inr(amount);
    }
}
