package com.luffy.tradeflow_order_service.matching;

import com.luffy.tradeflow.common.events.Events.OrderSide;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Settings of the simulated (DEMO only) order book.
 *
 * @param levels           price levels on each side of the book
 * @param levelNotional    typical value resting at the best level; deeper levels hold more
 * @param minLevelShares   smallest typical depth of a level, so very expensive shares still trade
 * @param levelStep        distance between levels, as a fraction of the touch (never below 0.05)
 * @param marketProtection how far from the accepted quote a MARKET order may trade, as a fraction.
 *                         A market buy reserves this buffer; whatever it doesn't use is returned.
 */
@ConfigurationProperties("tradeflow.orders.matching")
public record MatchingProperties(Integer levels, BigDecimal levelNotional, Long minLevelShares, BigDecimal levelStep,
                                 BigDecimal marketProtection) {

    public MatchingProperties {
        levels = levels == null ? 5 : levels;
        levelNotional = levelNotional == null ? new BigDecimal("50000") : levelNotional;
        minLevelShares = minLevelShares == null ? 5L : minLevelShares;
        levelStep = levelStep == null ? new BigDecimal("0.0005") : levelStep;
        marketProtection = marketProtection == null ? new BigDecimal("0.01") : marketProtection;
    }

    /** The worst price a MARKET order accepted at {@code touch} may trade at, on the paise grid. */
    public BigDecimal protectedPrice(OrderSide side, BigDecimal touch) {
        return side == OrderSide.BUY
                ? touch.multiply(BigDecimal.ONE.add(marketProtection)).setScale(2, RoundingMode.CEILING)
                : touch.multiply(BigDecimal.ONE.subtract(marketProtection)).setScale(2, RoundingMode.FLOOR)
                .max(new BigDecimal("0.01"));
    }
}
