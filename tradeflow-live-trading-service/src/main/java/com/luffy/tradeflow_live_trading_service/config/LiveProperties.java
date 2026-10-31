package com.luffy.tradeflow_live_trading_service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.Duration;

/** Platform-wide live controls. User risk limits can only be stricter than these. */
@ConfigurationProperties("tradeflow.live")
public record LiveProperties(String broker, BigDecimal platformMaxOrderValue, Integer platformMaxOrdersPerDay,
                             BigDecimal platformMaxDailyTurnover, Duration confirmationTtl, Duration stepUpMaxAge) {

    public LiveProperties {
        broker = broker == null || broker.isBlank() ? "none" : broker;
        platformMaxOrderValue = platformMaxOrderValue == null ? new BigDecimal("25000") : platformMaxOrderValue;
        platformMaxOrdersPerDay = platformMaxOrdersPerDay == null ? 10 : platformMaxOrdersPerDay;
        platformMaxDailyTurnover = platformMaxDailyTurnover == null ? new BigDecimal("100000") : platformMaxDailyTurnover;
        confirmationTtl = confirmationTtl == null ? Duration.ofSeconds(60) : confirmationTtl;
        stepUpMaxAge = stepUpMaxAge == null ? Duration.ofMinutes(10) : stepUpMaxAge;
        if (stepUpMaxAge.compareTo(Duration.ofMinutes(15)) > 0) {
            throw new IllegalStateException("tradeflow.live.step-up-max-age must be at most 15 minutes");
        }
    }
}
