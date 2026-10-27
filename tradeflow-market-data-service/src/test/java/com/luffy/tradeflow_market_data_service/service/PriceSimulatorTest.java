package com.luffy.tradeflow_market_data_service.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PriceSimulatorTest {

    private static final BigDecimal DRIFT = new BigDecimal("0.0002");
    private static final BigDecimal VOL = new BigDecimal("0.012");

    private static List<BigDecimal> path(long seed, String symbol, int ticks) {
        List<BigDecimal> prices = new ArrayList<>();
        BigDecimal p = new BigDecimal("1000.00");
        for (int t = 1; t <= ticks; t++) {
            p = PriceSimulator.nextPrice(seed, symbol, t, p, DRIFT, VOL);
            prices.add(p);
        }
        return prices;
    }

    @Test
    void sameSeedReplaysTheSamePath() {
        assertThat(path(42, "TCS", 200)).isEqualTo(path(42, "TCS", 200));
    }

    @Test
    void differentSeedsOrSymbolsDiverge() {
        assertThat(path(42, "TCS", 50)).isNotEqualTo(path(43, "TCS", 50));
        assertThat(path(42, "TCS", 50)).isNotEqualTo(path(42, "INFY", 50));
    }

    @Test
    void pricesStayPositiveWithTwoDecimalsAndBoundedMoves() {
        List<BigDecimal> prices = path(7, "SBIN", 2000);
        BigDecimal prev = new BigDecimal("1000.00");
        for (BigDecimal p : prices) {
            assertThat(p.signum()).isPositive();
            assertThat(p.scale()).isEqualTo(2);
            double move = p.doubleValue() / prev.doubleValue() - 1;
            assertThat(Math.abs(move)).isLessThanOrEqualTo(0.2001);
            prev = p;
        }
    }

    /**
     * Regression test: unscrambled seeds for consecutive ticks produced correlated
     * draws and prices trended strongly in one direction.
     */
    @Test
    void returnsAreRoughlyUnbiasedAndUncorrelated() {
        List<BigDecimal> prices = path(42, "TCS", 5000);
        double[] r = new double[prices.size() - 1];
        for (int i = 1; i < prices.size(); i++) {
            r[i - 1] = prices.get(i).doubleValue() / prices.get(i - 1).doubleValue() - 1;
        }
        double mean = 0;
        for (double x : r) {
            mean += x;
        }
        mean /= r.length;
        double var = 0;
        double cov = 0;
        for (int i = 0; i < r.length; i++) {
            var += (r[i] - mean) * (r[i] - mean);
            if (i > 0) {
                cov += (r[i] - mean) * (r[i - 1] - mean);
            }
        }
        double autocorrelation = cov / var;
        double sd = Math.sqrt(var / r.length);
        assertThat(mean).isBetween(-0.001, 0.0015);             // drift 0.0002 ± noise
        assertThat(sd).isBetween(0.010, 0.014);                 // volatility 0.012
        assertThat(Math.abs(autocorrelation)).isLessThan(0.05);
    }
}
