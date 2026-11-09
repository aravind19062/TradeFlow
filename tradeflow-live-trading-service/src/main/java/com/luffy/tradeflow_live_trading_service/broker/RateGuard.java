package com.luffy.tradeflow_live_trading_service.broker;

import com.luffy.tradeflow.common.web.ApiException;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;

/**
 * Keeps TradeFlow under a broker's rate limit by refusing locally: a request refused here is far
 * cheaper than one that gets the user's API access throttled or blocked by the broker. Limits are set
 * at or below the broker's published ones.
 */
public final class RateGuard {

    private final String broker;
    private final String name;
    private final int perSecond;
    private final int perMinute;
    private final Clock clock;
    private final ArrayDeque<Instant> calls = new ArrayDeque<>();

    public RateGuard(String broker, String name, int perSecond, int perMinute, Clock clock) {
        this.broker = broker;
        this.name = name;
        this.perSecond = perSecond;
        this.perMinute = perMinute;
        this.clock = clock;
    }

    public synchronized void acquire() {
        Instant now = clock.instant();
        while (!calls.isEmpty() && !calls.peekFirst().isAfter(now.minus(Duration.ofMinutes(1)))) {
            calls.pollFirst();
        }
        Instant secondAgo = now.minusSeconds(1);
        long lastSecond = calls.stream().filter(t -> t.isAfter(secondAgo)).count();
        if (calls.size() >= perMinute || lastSecond >= perSecond) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "BROKER_RATE_LIMITED",
                    "Too many requests to " + broker + " (" + name + "). Wait a minute and try again.");
        }
        calls.addLast(now);
    }
}
