package com.luffy.tradeflow_order_service.saga;

import java.util.EnumSet;
import java.util.Set;

/**
 * Where an order is in the cross-service workflow (choreography with an explicit,
 * order-service-owned progress record):
 * <pre>
 * AWAITING_RESERVATION ─reserved+fill──▶ AWAITING_SETTLEMENT ─trades.settled─▶ COMPLETED
 *        │             └reserved, resting▶ RESTING ─fill─▶ AWAITING_SETTLEMENT
 *        ├─rejected──────────────────────▶ REJECTED
 *        └─timeout──┐            RESTING ─cancel / expiry─┐
 *                   ▼                                     ▼
 *             RELEASE_REQUESTED ─orders.released─▶ RELEASED
 * AWAITING_SETTLEMENT ─max re-drives─▶ SETTLEMENT_FAILED   (trade is real: never released)
 * RELEASE_REQUESTED   ─max re-drives─▶ RELEASE_FAILED
 * </pre>
 */
public enum SagaState {
    AWAITING_RESERVATION, RESTING, AWAITING_SETTLEMENT, RELEASE_REQUESTED,
    COMPLETED, REJECTED, RELEASED, SETTLEMENT_FAILED, RELEASE_FAILED;

    /** States the recovery job watches (they have a deadline). */
    public static final Set<SagaState> IN_FLIGHT =
            EnumSet.of(AWAITING_RESERVATION, RESTING, AWAITING_SETTLEMENT, RELEASE_REQUESTED);

    /** States that need an operator (visible in health and the admin API). */
    public static final Set<SagaState> FAILED = EnumSet.of(SETTLEMENT_FAILED, RELEASE_FAILED);

    public boolean isInFlight() {
        return IN_FLIGHT.contains(this);
    }
}
