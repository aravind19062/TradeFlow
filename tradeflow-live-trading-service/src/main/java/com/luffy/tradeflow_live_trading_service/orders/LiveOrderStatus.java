package com.luffy.tradeflow_live_trading_service.orders;

import java.util.Set;

/**
 * Live order states. A broker adapter maps the broker's own vocabulary onto {@link #FROM_BROKER};
 * the rest are TradeFlow's own.
 *
 * <pre>
 *  SUBMITTING ──ack──▶ OPEN ─▶ PARTIALLY_FILLED ─▶ FILLED
 *      │  └─4xx──▶ REJECTED        │  └─ cancel ─▶ CANCEL_REQUESTED ─▶ CANCELLED
 *      └─timeout/5xx/crash──▶ UNKNOWN ──reconcile──▶ (found: broker's state)
 *                                    └─ not found after the absence window, or found twice ─▶ NEEDS_REVIEW
 *  NEEDS_REVIEW ──user, with step-up, after checking the broker──▶ RESOLVED_BY_USER
 * </pre>
 */
public final class LiveOrderStatus {

    public static final String SUBMITTING = "SUBMITTING";
    public static final String UNKNOWN = "UNKNOWN";
    public static final String NEEDS_REVIEW = "NEEDS_REVIEW";
    public static final String OPEN = "OPEN";
    public static final String PARTIALLY_FILLED = "PARTIALLY_FILLED";
    public static final String CANCEL_REQUESTED = "CANCEL_REQUESTED";
    public static final String FILLED = "FILLED";
    public static final String CANCELLED = "CANCELLED";
    public static final String REJECTED = "REJECTED";
    public static final String EXPIRED = "EXPIRED";
    public static final String RESOLVED_BY_USER = "RESOLVED_BY_USER";

    /** The only states an adapter may report for a broker order. */
    public static final Set<String> FROM_BROKER = Set.of(OPEN, PARTIALLY_FILLED, FILLED, CANCELLED, REJECTED, EXPIRED);

    /** The outcome at the broker is not established: the user may not place another live order. */
    public static final Set<String> UNRESOLVED = Set.of(SUBMITTING, UNKNOWN, NEEDS_REVIEW);

    public static final Set<String> TERMINAL = Set.of(FILLED, CANCELLED, REJECTED, EXPIRED, RESOLVED_BY_USER);

    /** Unresolved, or still alive at the broker: looked up by reconciliation. */
    public static final Set<String> RECONCILABLE = Set.of(SUBMITTING, UNKNOWN, NEEDS_REVIEW, OPEN, PARTIALLY_FILLED,
            CANCEL_REQUESTED);

    private LiveOrderStatus() {
    }
}
