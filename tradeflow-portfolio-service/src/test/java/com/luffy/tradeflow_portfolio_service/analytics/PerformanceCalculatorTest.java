package com.luffy.tradeflow_portfolio_service.analytics;

import com.luffy.tradeflow_portfolio_service.client.MarketDataClient.PricePoint;
import com.luffy.tradeflow_portfolio_service.domain.LedgerEntry;
import com.luffy.tradeflow_portfolio_service.web.PortfolioViews.PerformanceReport;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PerformanceCalculatorTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private static Instant t(int minutes) {
        return T0.plusSeconds(60L * minutes);
    }

    @Test
    void replaysLedgerAgainstPriceHistory() {
        List<LedgerEntry> ledger = List.of(
                LedgerEntry.deposit(1, new BigDecimal("1000.00"), new BigDecimal("1000.00"), t(0)),
                LedgerEntry.trade(1, LedgerEntry.Type.BUY, "TCS", 5, new BigDecimal("100.00"),
                        new BigDecimal("-500.00"), new BigDecimal("500.00"), null, UUID.randomUUID(), UUID.randomUUID(),
                        t(2)));
        Map<String, List<PricePoint>> history = Map.of("TCS", List.of(
                new PricePoint(1, new BigDecimal("999.00"), t(-10)),   // before the account: ignored
                new PricePoint(2, new BigDecimal("110.00"), t(3)),
                new PricePoint(3, new BigDecimal("80.00"), t(4)),
                new PricePoint(4, new BigDecimal("120.00"), t(5))));

        PerformanceReport r = PerformanceCalculator.replay(t(0), ledger, history, 100);

        assertThat(r.points()).extracting(p -> p.totalValue().toPlainString())
                .containsExactly("1000.00", "1000.00", "1050.00", "900.00", "1100.00");
        assertThat(r.startValue()).isEqualByComparingTo("1000.00");
        assertThat(r.endValue()).isEqualByComparingTo("1100.00");
        assertThat(r.changeRatio()).isEqualByComparingTo("0.1");
        assertThat(r.maxDrawdownRatio()).isEqualByComparingTo("0.142857"); // 1050 → 900
        assertThat(r.peakValue()).isEqualByComparingTo("1100.00");
    }

    @Test
    void downsamplingKeepsEndpoints() {
        List<LedgerEntry> ledger = List.of(
                LedgerEntry.deposit(1, new BigDecimal("1000.00"), new BigDecimal("1000.00"), t(0)),
                LedgerEntry.trade(1, LedgerEntry.Type.BUY, "TCS", 1, new BigDecimal("100.00"),
                        new BigDecimal("-100.00"), new BigDecimal("900.00"), null, UUID.randomUUID(), UUID.randomUUID(),
                        t(1)));
        List<PricePoint> ticks = new java.util.ArrayList<>();
        for (int i = 0; i < 50; i++) {
            ticks.add(new PricePoint(i, new BigDecimal(100 + i), t(2 + i)));
        }
        PerformanceReport r = PerformanceCalculator.replay(t(0), ledger, Map.of("TCS", ticks), 10);
        assertThat(r.pointCount()).isEqualTo(52);
        assertThat(r.points()).hasSize(10);
        assertThat(r.points().get(9).totalValue()).isEqualByComparingTo("1049.00");
    }
}
