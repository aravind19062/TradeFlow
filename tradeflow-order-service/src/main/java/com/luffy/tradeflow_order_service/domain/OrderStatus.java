package com.luffy.tradeflow_order_service.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Order lifecycle. Allowed transitions:
 * <pre>
 *   PENDING ──reserved──▶ OPEN ──marketable tick──▶ FILLED
 *      │  └──reserved + marketable──────────────────▶ FILLED
 *      └──rejected (funds/position, or reservation timeout)──▶ REJECTED
 *   OPEN ──cancel──▶ CANCELLED      OPEN ──time-to-live elapsed──▶ EXPIRED
 * </pre>
 * PENDING orders cannot be cancelled: their reservation is in flight in
 * portfolio-service. Partial fills are not modelled (each order fills in full).
 */
public enum OrderStatus {
    PENDING, OPEN, FILLED, REJECTED, CANCELLED, EXPIRED;

    public Set<OrderStatus> allowedNext() {
        return switch (this) {
            case PENDING -> EnumSet.of(OPEN, FILLED, REJECTED);
            case OPEN -> EnumSet.of(FILLED, CANCELLED, EXPIRED);
            case FILLED, REJECTED, CANCELLED, EXPIRED -> EnumSet.noneOf(OrderStatus.class);
        };
    }

    public boolean canTransitionTo(OrderStatus next) {
        return allowedNext().contains(next);
    }

    public boolean isTerminal() {
        return allowedNext().isEmpty();
    }
}
