package com.luffy.tradeflow_portfolio_service.analytics;

import com.luffy.tradeflow.common.money.Money;
import com.luffy.tradeflow_portfolio_service.client.MarketDataClient.PricePoint;
import com.luffy.tradeflow_portfolio_service.domain.LedgerEntry;
import com.luffy.tradeflow_portfolio_service.web.PortfolioViews.PerformancePoint;
import com.luffy.tradeflow_portfolio_service.web.PortfolioViews.PerformanceReport;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Rebuilds portfolio value over time by replaying the ledger against simulated
 * price history, so no snapshots need to be stored. At each point:
 * value = cash + Σ quantity × last known price (a trade's own price counts as
 * an observation until the next tick for that symbol arrives).
 */
public final class PerformanceCalculator {

    private PerformanceCalculator() {
    }

    private record Event(Instant at, int order, LedgerEntry entry, String symbol, BigDecimal price) {
    }

    public static PerformanceReport replay(Instant from, List<LedgerEntry> ledger,
                                           Map<String, List<PricePoint>> history, int maxPoints) {
        List<Event> events = new ArrayList<>();
        for (Map.Entry<String, List<PricePoint>> h : history.entrySet()) {
            for (PricePoint p : h.getValue()) {
                if (!p.asOf().isBefore(from)) {
                    events.add(new Event(p.asOf(), 0, null, h.getKey(), p.price()));
                }
            }
        }
        for (LedgerEntry e : ledger) {
            events.add(new Event(e.getOccurredAt(), 1, e, e.getSymbol(), e.getPrice()));
        }
        events.sort(Comparator.comparing(Event::at).thenComparingInt(Event::order));

        BigDecimal cash = BigDecimal.ZERO;
        Map<String, Long> quantities = new HashMap<>();
        Map<String, BigDecimal> prices = new HashMap<>();
        List<PerformancePoint> points = new ArrayList<>();
        int i = 0;
        while (i < events.size()) {
            Instant at = events.get(i).at();
            while (i < events.size() && events.get(i).at().equals(at)) {
                Event ev = events.get(i++);
                if (ev.entry() == null) {
                    prices.put(ev.symbol(), ev.price());
                } else {
                    LedgerEntry e = ev.entry();
                    cash = e.getCashBalanceAfter();
                    if (e.getSymbol() != null) {
                        quantities.merge(e.getSymbol(), e.quantityDelta(), Long::sum);
                        prices.putIfAbsent(e.getSymbol(), e.getPrice());
                    }
                }
            }
            BigDecimal holdings = BigDecimal.ZERO;
            for (Map.Entry<String, Long> q : quantities.entrySet()) {
                BigDecimal price = prices.get(q.getKey());
                if (price != null && q.getValue() != 0) {
                    holdings = holdings.add(price.multiply(BigDecimal.valueOf(q.getValue())));
                }
            }
            points.add(new PerformancePoint(at, Money.cash(cash), Money.cash(holdings), Money.cash(cash.add(holdings))));
        }

        List<String> notes = new ArrayList<>(List.of(
                "Values are rebuilt from the ledger and simulated prices; they are not real market returns.",
                "Return is simple (end − start) / start; the only external cash flow is the initial deposit."));
        if (points.isEmpty()) {
            notes.add("No history yet.");
            return new PerformanceReport(from, from, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    Money.ratio(BigDecimal.ZERO, BigDecimal.ONE), BigDecimal.ZERO,
                    Money.ratio(BigDecimal.ZERO, BigDecimal.ONE), 0, List.of(), "SIMULATED", notes);
        }
        List<BigDecimal> values = points.stream().map(PerformancePoint::totalValue).toList();
        BigDecimal start = values.get(0);
        BigDecimal end = values.get(values.size() - 1);
        BigDecimal peak = values.stream().max(Comparator.naturalOrder()).orElse(end);
        BigDecimal change = Money.cash(end.subtract(start));
        return new PerformanceReport(points.get(0).at(), points.get(points.size() - 1).at(), start, end, change,
                Money.ratio(change, start), peak, PortfolioAnalytics.maxDrawdown(values), points.size(),
                downsample(points, maxPoints), "SIMULATED", notes);
    }

    /** Keeps the first and last points and evenly spaced ones in between. */
    static List<PerformancePoint> downsample(List<PerformancePoint> points, int maxPoints) {
        if (points.size() <= maxPoints || maxPoints < 2) {
            return points;
        }
        List<PerformancePoint> result = new ArrayList<>(maxPoints);
        double step = (points.size() - 1) / (double) (maxPoints - 1);
        for (int k = 0; k < maxPoints; k++) {
            result.add(points.get((int) Math.round(k * step)));
        }
        return result;
    }
}
