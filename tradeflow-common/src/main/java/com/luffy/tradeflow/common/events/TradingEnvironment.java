package com.luffy.tradeflow.common.events;

/**
 * Which money an event, service or record belongs to. DEMO and LIVE never mix: a DEMO
 * service never accepts LIVE events and a LIVE service never accepts DEMO events.
 * PLATFORM services (users, learning) hold no trading data and are environment-neutral.
 */
public enum TradingEnvironment {
    /** Simulated prices and virtual cash (market-data, order and portfolio services). */
    DEMO,
    /** Real broker accounts and real money (live-trading-service). */
    LIVE,
    /** Neither: identity, education and readiness. */
    PLATFORM;

    /** Envelopes written before environments existed carry none; every one of them was DEMO. */
    public static TradingEnvironment ofEnvelope(String value) {
        return value == null ? DEMO : valueOf(value);
    }
}
