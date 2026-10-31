package com.luffy.tradeflow.common.money;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SimulatedSpreadTest {

    @Test
    void tenBasisPointsAroundTheMid() {
        var t = SimulatedSpread.of(new BigDecimal("1000.00"), 10);
        assertThat(t.bid()).isEqualByComparingTo("999.50");
        assertThat(t.ask()).isEqualByComparingTo("1000.50");
        assertThat(t.spread()).isEqualByComparingTo("1.00");
    }

    @Test
    void roundsOutwardSoTheSpreadNeverDisappears() {
        var t = SimulatedSpread.of(new BigDecimal("60.72"), 10);
        assertThat(t.bid()).isEqualByComparingTo("60.68");
        assertThat(t.ask()).isEqualByComparingTo("60.76");
        var tiny = SimulatedSpread.of(new BigDecimal("1.00"), 1);
        assertThat(tiny.ask().compareTo(tiny.bid())).isPositive();
    }

    @Test
    void zeroSpreadIsTheMid() {
        var t = SimulatedSpread.of(new BigDecimal("1234.5678"), 0);
        assertThat(t.bid()).isEqualByComparingTo("1234.56");
        assertThat(t.ask()).isEqualByComparingTo("1234.57");
        var exact = SimulatedSpread.of(new BigDecimal("1234.56"), 0);
        assertThat(exact.bid()).isEqualByComparingTo(exact.ask());
        assertThatThrownBy(() -> SimulatedSpread.of(BigDecimal.TEN, -1)).isInstanceOf(IllegalArgumentException.class);
    }
}
