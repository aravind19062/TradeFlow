package com.luffy.tradeflow_portfolio_service.domain;

import com.luffy.tradeflow.common.kafka.InconsistentStateException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountingMathTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void averageCostIsWeightedAcrossBuys() {
        Holding h = new Holding(1, "TCS", NOW);
        h.applyBuy(10, new BigDecimal("1000.00"), NOW);   // 10 @ 100
        h.applyBuy(5, new BigDecimal("650.00"), NOW);     // 5 @ 130
        assertThat(h.getQuantity()).isEqualTo(15);
        assertThat(h.getAverageCost()).isEqualByComparingTo("110.0000"); // 1650 / 15
        assertThat(h.costBasis()).isEqualByComparingTo("1650.00");
    }

    @Test
    void averageCostKeepsFourDecimals() {
        Holding h = new Holding(1, "TCS", NOW);
        h.applyBuy(3, new BigDecimal("100.00"), NOW);
        assertThat(h.getAverageCost()).isEqualByComparingTo("33.3333");
    }

    @Test
    void sellRealizesAgainstAverageCostAndKeepsAverage() {
        Holding h = new Holding(1, "TCS", NOW);
        h.applyBuy(10, new BigDecimal("1000.00"), NOW);
        h.reserve(4, NOW);
        BigDecimal realized = h.applySell(4, new BigDecimal("125.00"), NOW);
        assertThat(realized).isEqualByComparingTo("100.00");          // (125 − 100) × 4
        assertThat(h.getQuantity()).isEqualTo(6);
        assertThat(h.getReservedQuantity()).isZero();
        assertThat(h.getAverageCost()).isEqualByComparingTo("100.0000");
        assertThat(h.getRealizedPnl()).isEqualByComparingTo("100.00");
    }

    @Test
    void sellingAtALossRealizesNegativePnlAndFullExitResetsAverage() {
        Holding h = new Holding(1, "TCS", NOW);
        h.applyBuy(2, new BigDecimal("200.00"), NOW);
        h.reserve(2, NOW);
        assertThat(h.applySell(2, new BigDecimal("90.00"), NOW)).isEqualByComparingTo("-20.00");
        assertThat(h.getQuantity()).isZero();
        assertThat(h.getAverageCost()).isEqualByComparingTo("0");
    }

    @Test
    void cannotReserveMoreSharesThanAvailable() {
        Holding h = new Holding(1, "TCS", NOW);
        h.applyBuy(5, new BigDecimal("500.00"), NOW);
        h.reserve(3, NOW);
        assertThat(h.availableQuantity()).isEqualTo(2);
        assertThatThrownBy(() -> h.reserve(3, NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void accountReservesSettlesAndReleases() {
        Account a = Account.open(1, new BigDecimal("1000.00"), NOW);
        a.reserve(new BigDecimal("600.00"), NOW);
        assertThat(a.availableCash()).isEqualByComparingTo("400.00");
        assertThat(a.canReserve(new BigDecimal("400.01"))).isFalse();

        a.settleBuy(new BigDecimal("590.00"), new BigDecimal("600.00"), NOW); // filled below reservation
        assertThat(a.getCashBalance()).isEqualByComparingTo("410.00");
        assertThat(a.getReservedCash()).isEqualByComparingTo("0.00");
    }

    @Test
    void settlementAboveReservationIsAnInconsistency() {
        Account a = Account.open(1, new BigDecimal("1000.00"), NOW);
        a.reserve(new BigDecimal("100.00"), NOW);
        assertThatThrownBy(() -> a.settleBuy(new BigDecimal("100.01"), new BigDecimal("100.00"), NOW))
                .isInstanceOf(InconsistentStateException.class);
    }

    @Test
    void releasingMoreThanReservedIsAnInconsistency() {
        Account a = Account.open(1, new BigDecimal("1000.00"), NOW);
        assertThatThrownBy(() -> a.release(new BigDecimal("1.00"), NOW)).isInstanceOf(InconsistentStateException.class);
    }
}
