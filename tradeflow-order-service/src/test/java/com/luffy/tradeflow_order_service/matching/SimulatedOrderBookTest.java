package com.luffy.tradeflow_order_service.matching;

import com.luffy.tradeflow.common.events.Events.OrderSide;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SimulatedOrderBookTest {

    private static final MatchingProperties DEFAULTS = new MatchingProperties(null, null, null, null, null);
    private static final BigDecimal TOUCH = new BigDecimal("1000.00");

    private static SimulatedOrderBook book(OrderSide side, long tick, long consumed) {
        return new SimulatedOrderBook("TCS", tick, side, TOUCH, consumed, DEFAULTS);
    }

    private static long totalDepth(SimulatedOrderBook book) {
        long total = 0;
        for (int level = 0; level < DEFAULTS.levels(); level++) {
            total += book.depth(level);
        }
        return total;
    }

    @Test
    void theSameTickAlwaysGivesTheSameBookAndANewTickChangesIt() {
        assertThat(book(OrderSide.BUY, 7, 0).depth(0)).isEqualTo(book(OrderSide.BUY, 7, 0).depth(0));
        List<Long> depths = java.util.stream.LongStream.range(1, 20).map(t -> book(OrderSide.BUY, t, 0).depth(0))
                .boxed().distinct().toList();
        assertThat(depths).hasSizeGreaterThan(1);
    }

    @Test
    void depthStaysNearTheTypicalSizeAndGrowsWithDistance() {
        for (long tick = 0; tick < 200; tick++) {
            assertThat(book(OrderSide.SELL, tick, 0).depth(0)).isBetween(30L, 70L); // 50 typical at ₹1000
            assertThat(book(OrderSide.SELL, tick, 0).depth(4)).isBetween(90L, 210L);
        }
    }

    @Test
    void expensiveSharesStillHaveSomeDepth() {
        var book = new SimulatedOrderBook("MRF", 3, OrderSide.BUY, new BigDecimal("140000.00"), 0, DEFAULTS);
        assertThat(book.depth(0)).isBetween(3L, 7L);
    }

    @Test
    void buysWalkUpTheAsksAndSellsDownTheBids() {
        assertThat(book(OrderSide.BUY, 1, 0).price(2)).isEqualByComparingTo("1001.00");
        assertThat(book(OrderSide.SELL, 1, 0).price(2)).isEqualByComparingTo("999.00");
    }

    @Test
    void aSmallOrderFillsAtTheTouchInOneSlice() {
        var slices = book(OrderSide.BUY, 1, 0).take(10, p -> true);
        assertThat(slices).containsExactly(new SimulatedOrderBook.Slice(10, TOUCH));
    }

    @Test
    void aLargeOrderTakesEveryLevelAndNoMore() {
        var book = book(OrderSide.BUY, 1, 0);
        var slices = book.take(1_000_000, p -> true);
        assertThat(slices).hasSize(DEFAULTS.levels());
        assertThat(slices.stream().mapToLong(SimulatedOrderBook.Slice::quantity).sum()).isEqualTo(totalDepth(book));
        assertThat(slices).extracting(SimulatedOrderBook.Slice::price).isSorted();
        assertThat(book.take(1, p -> true)).isEmpty();
    }

    @Test
    void takingStopsAtTheFirstUnacceptablePrice() {
        var book = book(OrderSide.BUY, 1, 0);
        var slices = book.take(1_000_000, p -> p.compareTo(new BigDecimal("1000.50")) <= 0);
        assertThat(slices).extracting(SimulatedOrderBook.Slice::price)
                .containsExactly(new BigDecimal("1000.00"), new BigDecimal("1000.50"));
        assertThat(book.consumed()).isEqualTo(book.depth(0) + book.depth(1));
    }

    @Test
    void consumedLiquidityIsNotOfferedAgain() {
        long top = book(OrderSide.BUY, 1, 0).depth(0);
        var slices = book(OrderSide.BUY, 1, top - 3).take(5, p -> true);
        assertThat(slices).containsExactly(new SimulatedOrderBook.Slice(3, new BigDecimal("1000.00")),
                new SimulatedOrderBook.Slice(2, new BigDecimal("1000.50")));
    }

    @Test
    void protectionRoundsAgainstTheOrderSoFundsAreNeverShort() {
        assertThat(DEFAULTS.protectedPrice(OrderSide.BUY, new BigDecimal("333.33"))).isEqualByComparingTo("336.67");
        assertThat(DEFAULTS.protectedPrice(OrderSide.SELL, new BigDecimal("333.33"))).isEqualByComparingTo("329.99");
    }
}
