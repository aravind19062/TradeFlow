package com.luffy.tradeflow.common.money;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Simulated bid/ask around the simulator's mid price, so the demo shows the cost of crossing the
 * spread: buyers pay the ask, sellers receive the bid. The spread is a fixed number of basis points
 * of the mid, rounded outward to the paisa (bid down, ask up). With 0 bps, bid = ask = mid.
 */
public final class SimulatedSpread {

    public record Touch(BigDecimal bid, BigDecimal ask) {
        public BigDecimal spread() {
            return ask.subtract(bid);
        }
    }

    private SimulatedSpread() {
    }

    public static Touch of(BigDecimal mid, int spreadBps) {
        if (spreadBps < 0 || spreadBps > 500) {
            throw new IllegalArgumentException("spread must be between 0 and 500 bps");
        }
        BigDecimal half = mid.multiply(BigDecimal.valueOf(spreadBps)).divide(BigDecimal.valueOf(20_000), 8, RoundingMode.HALF_UP);
        BigDecimal bid = mid.subtract(half).setScale(2, RoundingMode.FLOOR);
        BigDecimal ask = mid.add(half).setScale(2, RoundingMode.CEILING);
        if (spreadBps > 0 && ask.compareTo(bid) <= 0) {
            ask = bid.add(new BigDecimal("0.01"));
        }
        return new Touch(bid, ask);
    }
}
