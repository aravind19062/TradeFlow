package com.luffy.tradeflow_market_data_service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param seed           random-walk seed; same seed ⇒ same price path on every machine
 * @param historyTicks   ticks generated on first start so charts/analytics have history
 * @param historySpacing simulated time between seeded history ticks
 * @param spreadBps      simulated bid/ask spread around the mid, in basis points (default 10 = 0.10%)
 */
@ConfigurationProperties("tradeflow.market")
public record MarketProperties(Long seed, Integer historyTicks, Duration historySpacing, Integer spreadBps) {

    public MarketProperties {
        seed = seed == null ? 42L : seed;
        historyTicks = historyTicks == null ? 90 : historyTicks;
        historySpacing = historySpacing == null ? Duration.ofDays(1) : historySpacing;
        spreadBps = spreadBps == null ? 10 : spreadBps;
        if (spreadBps < 0 || spreadBps > 500) {
            throw new IllegalStateException("tradeflow.market.spread-bps must be between 0 and 500");
        }
        if (historyTicks < 2) {
            throw new IllegalStateException("tradeflow.market.history-ticks must be at least 2");
        }
    }
}
