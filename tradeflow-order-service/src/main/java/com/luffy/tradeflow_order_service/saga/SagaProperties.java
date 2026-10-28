package com.luffy.tradeflow_order_service.saga;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param reservationTimeout how long an order may wait for portfolio's reserve/reject reply
 * @param settlementTimeout  how long a fill may wait for trades.settled before it is re-driven
 * @param releaseTimeout     how long a release request may wait for orders.released
 * @param limitOrderTtl      resting LIMIT orders expire (and release funds) after this
 * @param maxRedrives        re-sends of trades.executed / orders.cancelled before the saga is marked FAILED
 * @param scanIntervalMs     how often the recovery job looks for overdue sagas
 */
@ConfigurationProperties("tradeflow.saga")
public record SagaProperties(Duration reservationTimeout, Duration settlementTimeout, Duration releaseTimeout,
                             Duration limitOrderTtl, Integer maxRedrives, Long scanIntervalMs) {

    public SagaProperties {
        reservationTimeout = reservationTimeout == null ? Duration.ofMinutes(2) : reservationTimeout;
        settlementTimeout = settlementTimeout == null ? Duration.ofMinutes(2) : settlementTimeout;
        releaseTimeout = releaseTimeout == null ? Duration.ofMinutes(2) : releaseTimeout;
        limitOrderTtl = limitOrderTtl == null ? Duration.ofDays(1) : limitOrderTtl;
        maxRedrives = maxRedrives == null ? 3 : maxRedrives;
        scanIntervalMs = scanIntervalMs == null ? 10_000L : scanIntervalMs;
    }
}
