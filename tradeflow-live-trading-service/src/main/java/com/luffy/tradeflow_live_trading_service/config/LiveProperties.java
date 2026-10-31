package com.luffy.tradeflow_live_trading_service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * Platform-wide live controls. User risk limits can only be stricter than these.
 *
 * @param reconcileInFlightGrace an order still SUBMITTING after this long is treated as interrupted
 *                               (its broker call is far shorter than this)
 * @param reconcileAbsenceWindow how long an order of unknown outcome may be missing from the broker's
 *                               order book before the user is asked to check (brokers can list a new
 *                               order late); until then it stays UNKNOWN and is looked for again
 */
@ConfigurationProperties("tradeflow.live")
public record LiveProperties(String broker, BigDecimal platformMaxOrderValue, Integer platformMaxOrdersPerDay,
                             BigDecimal platformMaxDailyTurnover, Duration confirmationTtl, Duration stepUpMaxAge,
                             Duration reconcileInFlightGrace, Duration reconcileAbsenceWindow) {


    public LiveProperties {
        broker = broker == null || broker.isBlank() ? "none" : broker;
        platformMaxOrderValue = platformMaxOrderValue == null ? new BigDecimal("25000") : platformMaxOrderValue;
        platformMaxOrdersPerDay = platformMaxOrdersPerDay == null ? 10 : platformMaxOrdersPerDay;
        platformMaxDailyTurnover = platformMaxDailyTurnover == null ? new BigDecimal("100000") : platformMaxDailyTurnover;
        confirmationTtl = confirmationTtl == null ? Duration.ofSeconds(60) : confirmationTtl;
        stepUpMaxAge = stepUpMaxAge == null ? Duration.ofMinutes(10) : stepUpMaxAge;
        reconcileInFlightGrace = reconcileInFlightGrace == null ? Duration.ofMinutes(2) : reconcileInFlightGrace;
        reconcileAbsenceWindow = reconcileAbsenceWindow == null ? Duration.ofMinutes(15) : reconcileAbsenceWindow;
        if (stepUpMaxAge.compareTo(Duration.ofMinutes(15)) > 0) {
            throw new IllegalStateException("tradeflow.live.step-up-max-age must be at most 15 minutes");
        }
    }
}
