package com.luffy.tradeflow_market_data_service.service;

import com.luffy.tradeflow.common.money.Money;

import java.math.BigDecimal;
import java.util.Random;

/**
 * Deterministic geometric random walk. The price at tick {@code n} depends only on
 * (seed, symbol, n, price at n-1), and {@link Random} is specified to produce the
 * same sequence for the same seed on every JVM, so a given seed always replays the
 * same market. These are simulated prices, not market data.
 */
public final class PriceSimulator {

    /** Caps a single tick's move at ±20% so an extreme draw cannot wipe out a price. */
    private static final double MAX_MOVE = 0.20;
    private static final BigDecimal MIN_PRICE = new BigDecimal("0.05");

    private PriceSimulator() {
    }

    public static BigDecimal nextPrice(long seed, String symbol, long tick, BigDecimal previous,
                                       BigDecimal drift, BigDecimal volatility) {
        double z = new Random(mix(seed, symbol, tick)).nextGaussian();
        double move = drift.doubleValue() + volatility.doubleValue() * z;
        move = Math.max(-MAX_MOVE, Math.min(MAX_MOVE, move));
        BigDecimal next = Money.price(previous.multiply(BigDecimal.valueOf(1.0 + move)));
        return next.max(MIN_PRICE);
    }

    /**
     * Combines the inputs and scrambles them with the SplitMix64 finalizer. Without the
     * scramble, consecutive ticks give nearly identical Random seeds, and Random's first
     * output is strongly correlated for nearby seeds, which made prices trend.
     */
    static long mix(long seed, String symbol, long tick) {
        long h = seed;
        h = h * 1_000_003L ^ symbol.hashCode();
        h = h * 1_000_003L ^ tick;
        h = (h ^ (h >>> 30)) * 0xbf58476d1ce4e5b9L;
        h = (h ^ (h >>> 27)) * 0x94d049bb133111ebL;
        return h ^ (h >>> 31);
    }
}
