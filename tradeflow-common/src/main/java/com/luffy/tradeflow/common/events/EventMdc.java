package com.luffy.tradeflow.common.events;

import org.slf4j.MDC;

/** Tags log lines written while handling an event with its id, mirroring X-Request-Id for HTTP. */
public final class EventMdc {

    public static final String KEY = "eventId";

    private EventMdc() {
    }

    public static void run(EventEnvelope envelope, Runnable action) {
        MDC.put(KEY, envelope.eventType() + ":" + envelope.eventId());
        try {
            action.run();
        } finally {
            MDC.remove(KEY);
        }
    }
}
