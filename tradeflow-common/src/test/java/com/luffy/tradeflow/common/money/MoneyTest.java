package com.luffy.tradeflow.common.money;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class MoneyTest {

    @Test
    void usesBankersRounding() {
        assertThat(Money.cash(new BigDecimal("2.345"))).isEqualByComparingTo("2.34");
        assertThat(Money.cash(new BigDecimal("2.355"))).isEqualByComparingTo("2.36");
    }

    @Test
    void amountIsQuantityTimesPriceAtCashScale() {
        assertThat(Money.amount(3, new BigDecimal("1234.565"))).isEqualByComparingTo("3703.70");
        assertThat(Money.amount(10, new BigDecimal("9554.22")).scale()).isEqualTo(2);
    }

    @Test
    void ratioIsZeroForZeroDenominator() {
        assertThat(Money.ratio(BigDecimal.ONE, BigDecimal.ZERO)).isEqualByComparingTo("0");
        assertThat(Money.ratio(BigDecimal.ONE, new BigDecimal("3"))).isEqualByComparingTo("0.333333");
    }
}
