package com.luffy.tradeflow_order_service.matching;

import com.luffy.tradeflow.common.events.Events.OrderSide;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.function.Predicate;

/**
 * One side of the simulated market for one tick, used only by DEMO orders. TradeFlow has no other
 * traders, so this stands in for them: limited shares at the touch and at a few worse prices.
 *
 * <p>The depth of each level depends only on the symbol, the tick, the side and the level, so a tick
 * always produces the same book. What has been taken is a single running total of shares, consumed
 * from the best level down, and shared by every order in the tick. A new tick is a fresh book.
 */
public final class SimulatedOrderBook {

    /** Part of an order executed at one price level. */
    public record Slice(long quantity, BigDecimal price) {
    }

    private static final BigDecimal MIN_STEP = new BigDecimal("0.05");

    private final String symbol;
    private final long tick;
    private final OrderSide takerSide;
    private final BigDecimal touch;
    private final MatchingProperties properties;
    private long consumed;

    /**
     * @param takerSide the side of the orders executing here (a BUY takes from the asks)
     * @param touch     the best price for that side: the ask for a BUY, the bid for a SELL
     * @param consumed  shares already taken from this side during this tick
     */
    public SimulatedOrderBook(String symbol, long tick, OrderSide takerSide, BigDecimal touch, long consumed,
                              MatchingProperties properties) {
        this.symbol = symbol;
        this.tick = tick;
        this.takerSide = takerSide;
        this.touch = touch;
        this.consumed = consumed;
        this.properties = properties;
    }

    /**
     * Takes up to {@code quantity} shares, best price first, and stops at the first level the order
     * won't accept. Anything not taken stays with the order for the next tick.
     */
    public List<Slice> take(long quantity, Predicate<BigDecimal> acceptable) {
        List<Slice> slices = new ArrayList<>();
        long remaining = quantity;
        long levelStart = 0;
        for (int level = 0; level < properties.levels() && remaining > 0; level++) {
            long levelEnd = levelStart + depth(level);
            long available = levelEnd - Math.max(consumed, levelStart);
            levelStart = levelEnd;
            if (available <= 0) {
                continue;
            }
            BigDecimal price = price(level);
            if (price.signum() <= 0 || !acceptable.test(price)) {
                break;
            }
            long taken = Math.min(available, remaining);
            consumed += taken;
            remaining -= taken;
            slices.add(new Slice(taken, price));
        }
        return slices;
    }

    public long consumed() {
        return consumed;
    }

    BigDecimal price(int level) {
        BigDecimal step = touch.multiply(properties.levelStep()).setScale(2, RoundingMode.CEILING).max(MIN_STEP)
                .multiply(BigDecimal.valueOf(level));
        return takerSide == OrderSide.BUY ? touch.add(step) : touch.subtract(step);
    }

    /** Between 60% and 140% of the typical size; each level deeper holds half as much again. */
    long depth(int level) {
        long typical = Math.max(properties.minLevelShares(),
                properties.levelNotional().divide(touch, 0, RoundingMode.DOWN).longValue());
        double jitter = 0.6 + 0.8 * new SplittableRandom(seed(level)).nextDouble();
        return Math.max(1, Math.round(typical * jitter * (1 + level * 0.5)));
    }

    private long seed(int level) {
        return 31L * (31L * (31L * symbol.hashCode() + tick) + takerSide.ordinal()) + level;
    }
}
