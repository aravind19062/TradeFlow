package com.luffy.tradeflow.common.money;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Rounding rules for all monetary arithmetic. Everything is {@link BigDecimal};
 * rounding happens once, at the boundary where a value is stored or reported.
 *
 * <ul>
 *   <li>Cash amounts and prices: 2 decimals (paise/cents).</li>
 *   <li>Average cost per share: 4 decimals, so repeated buys do not drift.</li>
 *   <li>Ratios (weights, returns): 6 decimals.</li>
 * </ul>
 * HALF_EVEN (banker's rounding) avoids the upward bias of HALF_UP across many trades.
 */
public final class Money {

    public static final int CASH_SCALE = 2;
    public static final int PRICE_SCALE = 2;
    public static final int COST_SCALE = 4;
    public static final int RATIO_SCALE = 6;
    public static final RoundingMode ROUNDING = RoundingMode.HALF_EVEN;

    private Money() {
    }

    public static BigDecimal cash(BigDecimal value) {
        return value.setScale(CASH_SCALE, ROUNDING);
    }

    public static BigDecimal price(BigDecimal value) {
        return value.setScale(PRICE_SCALE, ROUNDING);
    }

    public static BigDecimal cost(BigDecimal value) {
        return value.setScale(COST_SCALE, ROUNDING);
    }

    /** numerator / denominator at ratio scale; zero when the denominator is zero. */
    public static BigDecimal ratio(BigDecimal numerator, BigDecimal denominator) {
        if (denominator.signum() == 0) {
            return BigDecimal.ZERO.setScale(RATIO_SCALE);
        }
        return numerator.divide(denominator, RATIO_SCALE, ROUNDING);
    }

    /** quantity × price, rounded to cash scale. */
    public static BigDecimal amount(long quantity, BigDecimal price) {
        return cash(price.multiply(BigDecimal.valueOf(quantity)));
    }
}
