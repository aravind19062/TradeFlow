package com.luffy.tradeflow_live_trading_service.broker.fyers;

import com.luffy.tradeflow.common.web.ApiException;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;

/**
 * Keeps TradeFlow under a Fyers rate limit by refusing locally. Fyers blocks the user for the rest
 * of the day after the per-minute limit is exceeded more than three times, so a refused request here
 * is far cheaper than one that reaches Fyers. Limits are set a little below the published ones.
 */
final class RateGuard {

    private final String name;
    private final int perSecond;
    private final int perMinute;
    private final Clock clock;
    private final ArrayDeque<Instant> calls = new ArrayDeque<>();

    RateGuard(String name, int perSecond, int perMinute, Clock clock) {
        this.name = name;
        this.perSecond = perSecond;
        this.perMinute = perMinute;
        this.clock = clock;
    }

    synchronized void acquire() {
        Instant now = clock.instant();
        while (!calls.isEmpty() && !calls.peekFirst().isAfter(now.minus(Duration.ofMinutes(1)))) {
            calls.pollFirst();
        }
        Instant secondAgo = now.minusSeconds(1);
        long lastSecond = calls.stream().filter(t -> t.isAfter(secondAgo)).count();
        if (calls.size() >= perMinute || lastSecond >= perSecond) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "BROKER_RATE_LIMITED",
                    "Too many requests to Fyers (" + name + "). Wait a minute and try again.");
        }
        calls.addLast(now);
    }
}
