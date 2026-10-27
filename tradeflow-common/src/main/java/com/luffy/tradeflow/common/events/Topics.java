package com.luffy.tradeflow.common.events;

import java.util.List;

/**
 * Kafka topic names. Each topic carries exactly one event type, and the topic
 * name doubles as the {@code eventType} in the {@link EventEnvelope}.
 */
public final class Topics {

    public static final String USERS_REGISTERED = "users.registered";
    public static final String ORDERS_PLACED = "orders.placed";
    public static final String ORDERS_RESERVED = "orders.reserved";
    public static final String ORDERS_REJECTED = "orders.rejected";
    public static final String ORDERS_CANCELLED = "orders.cancelled";
    public static final String TRADES_EXECUTED = "trades.executed";
    public static final String PRICES_UPDATED = "prices.updated";

    /** Suffix used by Spring Kafka's DeadLetterPublishingRecoverer. */
    public static final String DLT_SUFFIX = ".DLT";

    public static final List<String> ALL = List.of(
            USERS_REGISTERED, ORDERS_PLACED, ORDERS_RESERVED, ORDERS_REJECTED,
            ORDERS_CANCELLED, TRADES_EXECUTED, PRICES_UPDATED);

    private Topics() {
    }
}
