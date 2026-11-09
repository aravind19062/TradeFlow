package com.luffy.tradeflow_order_service.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Order lifecycle. Allowed transitions:
 * <pre>
 *   PENDING ──reserved──▶ OPEN ──marketable tick──▶ FILLED
 *      │  └──reserved + enough liquidity────────────▶ FILLED
 *      ├──reserved + some liquidity──▶ PARTIALLY_FILLED ──later ticks──▶ FILLED
 *      └──rejected (funds/position, or reservation timeout)──▶ REJECTED
 *   OPEN ──some liquidity──▶ PARTIALLY_FILLED (stays there across further partial fills)
 *   OPEN or PARTIALLY_FILLED ──cancel──▶ CANCELLED
 *   OPEN or PARTIALLY_FILLED ──time-to-live elapsed──▶ EXPIRED
 * </pre>
 * PENDING orders cannot be cancelled: their reservation is in flight in portfolio-service.
 * A CANCELLED or EXPIRED order can have filledQuantity above zero: those trades stand and only the
 * unfilled remainder is released.
 */
public enum OrderStatus {
    PENDING, OPEN, PARTIALLY_FILLED, FILLED, REJECTED, CANCELLED, EXPIRED;

    public Set<OrderStatus> allowedNext() {
        return switch (this) {
            case PENDING -> EnumSet.of(OPEN, PARTIALLY_FILLED, FILLED, REJECTED);
            case OPEN -> EnumSet.of(PARTIALLY_FILLED, FILLED, CANCELLED, EXPIRED);
            case PARTIALLY_FILLED -> EnumSet.of(PARTIALLY_FILLED, FILLED, CANCELLED, EXPIRED);
            case FILLED, REJECTED, CANCELLED, EXPIRED -> EnumSet.noneOf(OrderStatus.class);
        };
    }

    public boolean canTransitionTo(OrderStatus next) {
        return allowedNext().contains(next);
    }

    /** Reserved and able to execute on a later tick. */
    public boolean isWorking() {
        return this == OPEN || this == PARTIALLY_FILLED;
    }

    public boolean isTerminal() {
        return allowedNext().isEmpty();
    }
}
