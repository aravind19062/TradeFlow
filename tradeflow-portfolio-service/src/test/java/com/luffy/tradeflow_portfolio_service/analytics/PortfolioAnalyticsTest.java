package com.luffy.tradeflow_portfolio_service.analytics;

import com.luffy.tradeflow_portfolio_service.client.MarketDataClient.Quote;
import com.luffy.tradeflow_portfolio_service.domain.Account;
import com.luffy.tradeflow_portfolio_service.domain.Holding;
import com.luffy.tradeflow_portfolio_service.web.PortfolioViews.PortfolioSummary;
import com.luffy.tradeflow_portfolio_service.web.PortfolioViews.RiskReport;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PortfolioAnalyticsTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private static Quote quote(String symbol, String sector, String price) {
        return new Quote(symbol, symbol + " Ltd", sector, "EQUITY", "INR", new BigDecimal(price), new BigDecimal(price),
                BigDecimal.ZERO, BigDecimal.ZERO, 1, NOW, "SIMULATED");
    }

    /** Cash 10,000 of 20,000 deposit spent on 50 TCS @ 100 and 50 INFY @ 100. */
    private static PortfolioSummary sample(Map<String, Quote> quotes, boolean available) {
        Account account = Account.open(1, new BigDecimal("20000.00"), NOW);
        account.reserve(new BigDecimal("10000.00"), NOW);
        account.settleBuy(new BigDecimal("10000.00"), new BigDecimal("10000.00"), NOW);
        Holding tcs = new Holding(1, "TCS", NOW);
        tcs.applyBuy(50, new BigDecimal("5000.00"), NOW);
        Holding infy = new Holding(1, "INFY", NOW);
        infy.applyBuy(50, new BigDecimal("5000.00"), NOW);
        return PortfolioAnalytics.summarize(account, List.of(tcs, infy), quotes, available, "INR");
    }

    @Test
    void summaryValuesHoldingsAtMarketAndComputesPnl() {
        PortfolioSummary s = sample(Map.of(
                "TCS", quote("TCS", "IT", "120.00"),
                "INFY", quote("INFY", "IT", "90.00")), true);

        assertThat(s.cashBalance()).isEqualByComparingTo("10000.00");
        assertThat(s.holdingsMarketValue()).isEqualByComparingTo("10500.00"); // 6000 + 4500
        assertThat(s.holdingsCostBasis()).isEqualByComparingTo("10000.00");
        assertThat(s.unrealizedPnl()).isEqualByComparingTo("500.00");
        assertThat(s.totalValue()).isEqualByComparingTo("20500.00");
        assertThat(s.totalPnl()).isEqualByComparingTo("500.00");
        assertThat(s.totalReturnRatio()).isEqualByComparingTo("0.025");
        assertThat(s.holdings()).extracting(h -> h.symbol()).containsExactly("TCS", "INFY"); // by value desc
        assertThat(s.holdings().get(0).unrealizedPnl()).isEqualByComparingTo("1000.00");
        assertThat(s.holdings().get(1).unrealizedPnlRatio()).isEqualByComparingTo("-0.1");
    }

    @Test
    void missingMarketDataFallsBackToCostAndSaysSo() {
        PortfolioSummary s = sample(Map.of(), false);
        assertThat(s.holdingsMarketValue()).isEqualByComparingTo("10000.00");
        assertThat(s.unrealizedPnl()).isEqualByComparingTo("0.00");
        assertThat(s.pricesAvailable()).isFalse();
        assertThat(s.notes()).anyMatch(n -> n.contains("valued at average cost"));
    }

    @Test
    void riskReportsConcentrationAndWarnings() {
        PortfolioSummary s = sample(Map.of(
                "TCS", quote("TCS", "IT", "100.00"),
                "INFY", quote("INFY", "IT", "100.00")), true);
        RiskReport r = PortfolioAnalytics.risk(s, new BigDecimal("0.05"));

        assertThat(r.cashWeight()).isEqualByComparingTo("0.5");
        assertThat(r.herfindahlIndex()).isEqualByComparingTo("0.5");      // two equal positions
        assertThat(r.effectiveNumberOfHoldings()).isEqualByComparingTo("2.00");
        assertThat(r.largestPositionWeight()).isEqualByComparingTo("0.25");
        assertThat(r.bySector()).singleElement().satisfies(sl -> assertThat(sl.weight()).isEqualByComparingTo("0.5"));
        assertThat(r.warnings()).anyMatch(w -> w.startsWith("Sector IT"));
        assertThat(r.warnings()).noneMatch(w -> w.startsWith("TCS")); // exactly 25% is not above the limit
        assertThat(r.maxDrawdownRatio()).isEqualByComparingTo("0.05");
    }

    @Test
    void herfindahlOfSinglePositionIsOne() {
        assertThat(PortfolioAnalytics.herfindahl(List.of(BigDecimal.ONE))).isEqualByComparingTo("1");
        assertThat(PortfolioAnalytics.herfindahl(List.of())).isEqualByComparingTo("0");
    }

    @Test
    void maxDrawdownIsLargestPeakToTroughFall() {
        List<BigDecimal> values = List.of("100", "120", "90", "110", "130", "104").stream().map(BigDecimal::new).toList();
        // peak 120 → 90 is 25%; later 130 → 104 is 20%
        assertThat(PortfolioAnalytics.maxDrawdown(values)).isEqualByComparingTo("0.25");
        assertThat(PortfolioAnalytics.maxDrawdown(List.of(BigDecimal.ONE, BigDecimal.TEN))).isEqualByComparingTo("0");
    }
}
