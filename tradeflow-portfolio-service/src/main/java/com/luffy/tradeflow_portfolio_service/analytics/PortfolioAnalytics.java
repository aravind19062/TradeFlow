package com.luffy.tradeflow_portfolio_service.analytics;

import com.luffy.tradeflow.common.money.Money;
import com.luffy.tradeflow_portfolio_service.client.MarketDataClient.Quote;
import com.luffy.tradeflow_portfolio_service.domain.Account;
import com.luffy.tradeflow_portfolio_service.domain.Holding;
import com.luffy.tradeflow_portfolio_service.web.PortfolioViews.AllocationSlice;
import com.luffy.tradeflow_portfolio_service.web.PortfolioViews.HoldingView;
import com.luffy.tradeflow_portfolio_service.web.PortfolioViews.PortfolioSummary;
import com.luffy.tradeflow_portfolio_service.web.PortfolioViews.RiskReport;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Pure portfolio calculations: no I/O, deterministic, unit-tested.
 * Values are rounded only when reported (see {@link Money}).
 */
public final class PortfolioAnalytics {

    /** A single position above this share of total value triggers a concentration warning. */
    public static final BigDecimal POSITION_LIMIT = new BigDecimal("0.25");
    /** A single sector above this share of total value triggers a warning. */
    public static final BigDecimal SECTOR_LIMIT = new BigDecimal("0.40");

    public static final List<String> RISK_METHODOLOGY = List.of(
            "Weights are market value divided by total portfolio value (cash included).",
            "Herfindahl index (HHI) = sum of squared position weights within invested holdings; 1.0 means a "
                    + "single position, 1/N means N equal positions.",
            "Effective number of holdings = 1 / HHI.",
            "Max drawdown = largest peak-to-trough fall of total portfolio value over the available "
                    + "simulated history, as a fraction of the peak.",
            "All prices are simulated; these metrics describe the demo portfolio, not real markets.");

    private PortfolioAnalytics() {
    }

    public static PortfolioSummary summarize(Account account, List<Holding> holdings, Map<String, Quote> quotes,
                                             boolean pricesAvailable, String currency) {
        List<Holding> open = holdings.stream().filter(h -> h.getQuantity() > 0).toList();
        BigDecimal marketValue = BigDecimal.ZERO;
        BigDecimal costBasis = BigDecimal.ZERO;
        BigDecimal realized = account.getRealizedPnl();
        Map<Holding, BigDecimal> prices = new LinkedHashMap<>();
        Instant asOf = null;
        for (Holding h : open) {
            Quote q = quotes.get(h.getSymbol());
            BigDecimal price = q != null ? q.price() : h.getAverageCost();
            if (q != null && (asOf == null || q.asOf().isAfter(asOf))) {
                asOf = q.asOf();
            }
            prices.put(h, price);
            marketValue = marketValue.add(price.multiply(BigDecimal.valueOf(h.getQuantity())));
            costBasis = costBasis.add(h.costBasis());
        }
        BigDecimal totalValue = account.getCashBalance().add(marketValue);
        List<HoldingView> views = new ArrayList<>();
        for (Holding h : open) {
            Quote q = quotes.get(h.getSymbol());
            BigDecimal price = prices.get(h);
            BigDecimal value = Money.cash(price.multiply(BigDecimal.valueOf(h.getQuantity())));
            BigDecimal unrealized = Money.cash(value.subtract(h.costBasis()));
            views.add(new HoldingView(h.getSymbol(), q == null ? null : q.name(), q == null ? null : q.sector(),
                    q == null ? null : q.assetClass(), h.getQuantity(), h.getReservedQuantity(), h.getAverageCost(),
                    h.costBasis(), Money.price(price), value, unrealized, Money.ratio(unrealized, h.costBasis()),
                    Money.cash(h.getRealizedPnl()), Money.ratio(value, totalValue)));
        }
        views.sort(Comparator.comparing(HoldingView::marketValue).reversed());
        BigDecimal unrealized = Money.cash(marketValue.subtract(costBasis));
        BigDecimal totalPnl = Money.cash(totalValue.subtract(account.getNetDeposits()));
        List<String> notes = new ArrayList<>();
        notes.add("Prices are simulated demo data, not live market data.");
        if (!pricesAvailable) {
            notes.add("Market data was unavailable: holdings are valued at average cost and unrealized P&L is 0.");
        }
        return new PortfolioSummary(account.getUserId(), currency, Money.cash(account.getCashBalance()),
                Money.cash(account.getReservedCash()), Money.cash(account.availableCash()), Money.cash(marketValue),
                Money.cash(costBasis), Money.cash(totalValue), Money.cash(account.getNetDeposits()),
                Money.cash(realized), unrealized, totalPnl, Money.ratio(totalPnl, account.getNetDeposits()), views,
                pricesAvailable, asOf, "SIMULATED", notes);
    }

    public static RiskReport risk(PortfolioSummary summary, BigDecimal maxDrawdown) {
        BigDecimal total = summary.totalValue();
        BigDecimal invested = summary.holdingsMarketValue();
        List<AllocationSlice> bySymbol = summary.holdings().stream()
                .map(h -> new AllocationSlice(h.symbol(), h.marketValue(), Money.ratio(h.marketValue(), total)))
                .toList();
        Map<String, BigDecimal> sectors = new LinkedHashMap<>();
        Map<String, BigDecimal> assetClasses = new LinkedHashMap<>();
        for (HoldingView h : summary.holdings()) {
            sectors.merge(Objects.requireNonNullElse(h.sector(), "Unknown"), h.marketValue(), BigDecimal::add);
            assetClasses.merge(Objects.requireNonNullElse(h.assetClass(), "Unknown"), h.marketValue(),
                    BigDecimal::add);
        }
        assetClasses.merge("CASH", summary.cashBalance(), BigDecimal::add);

        List<BigDecimal> investedWeights = summary.holdings().stream()
                .map(h -> Money.ratio(h.marketValue(), invested)).toList();
        BigDecimal hhi = herfindahl(investedWeights);
        HoldingView largest = summary.holdings().isEmpty() ? null : summary.holdings().get(0);

        List<String> warnings = new ArrayList<>();
        for (AllocationSlice s : bySymbol) {
            if (s.weight().compareTo(POSITION_LIMIT) > 0) {
                warnings.add(s.name() + " is " + percent(s.weight()) + " of the portfolio (above "
                        + percent(POSITION_LIMIT) + ")");
            }
        }
        List<AllocationSlice> sectorSlices = slices(sectors, total);
        for (AllocationSlice s : sectorSlices) {
            if (s.weight().compareTo(SECTOR_LIMIT) > 0) {
                warnings.add("Sector " + s.name() + " is " + percent(s.weight()) + " of the portfolio (above "
                        + percent(SECTOR_LIMIT) + ")");
            }
        }
        if (!summary.pricesAvailable()) {
            warnings.add("Market data unavailable: weights use cost basis");
        }
        return new RiskReport(total, Money.ratio(summary.cashBalance(), total), Money.ratio(invested, total),
                largest == null ? null : largest.symbol(),
                largest == null ? Money.ratio(BigDecimal.ZERO, BigDecimal.ONE) : Money.ratio(largest.marketValue(), total),
                hhi, hhi.signum() == 0 ? BigDecimal.ZERO.setScale(2) : BigDecimal.ONE.divide(hhi, 2, Money.ROUNDING),
                bySymbol, sectorSlices, slices(assetClasses, total), maxDrawdown, warnings, RISK_METHODOLOGY,
                summary.pricesAvailable());
    }

    /** Σ wᵢ²; 0 for an empty portfolio. */
    public static BigDecimal herfindahl(List<BigDecimal> weights) {
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal w : weights) {
            sum = sum.add(w.multiply(w));
        }
        return sum.setScale(Money.RATIO_SCALE, Money.ROUNDING);
    }

    /** Largest (peak − trough) / peak over the series, as a non-negative ratio; 0 if it never falls. */
    public static BigDecimal maxDrawdown(List<BigDecimal> values) {
        BigDecimal peak = null;
        BigDecimal worst = BigDecimal.ZERO;
        for (BigDecimal v : values) {
            if (peak == null || v.compareTo(peak) > 0) {
                peak = v;
            }
            if (peak.signum() > 0) {
                BigDecimal drawdown = peak.subtract(v).divide(peak, Money.RATIO_SCALE + 4, Money.ROUNDING);
                if (drawdown.compareTo(worst) > 0) {
                    worst = drawdown;
                }
            }
        }
        return worst.setScale(Money.RATIO_SCALE, Money.ROUNDING);
    }

    private static List<AllocationSlice> slices(Map<String, BigDecimal> values, BigDecimal total) {
        return values.entrySet().stream()
                .map(e -> new AllocationSlice(e.getKey(), Money.cash(e.getValue()), Money.ratio(e.getValue(), total)))
                .sorted(Comparator.comparing(AllocationSlice::value).reversed())
                .toList();
    }

    public static String percent(BigDecimal ratio) {
        return ratio.multiply(BigDecimal.valueOf(100)).setScale(1, Money.ROUNDING) + "%";
    }
}
