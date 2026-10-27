package com.luffy.tradeflow_order_service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * @param marketDataUrl      base URL of tradeflow-market-data-service
 * @param maxQuantity        largest quantity accepted in one order
 * @param maxNotional        largest order value (quantity × reservation price) accepted
 * @param stuckPendingAfter  PENDING orders older than this are reported by the health check
 */
@ConfigurationProperties("tradeflow.orders")
public record OrderProperties(String marketDataUrl, Long maxQuantity, BigDecimal maxNotional,
                              Duration stuckPendingAfter) {

    public OrderProperties {
        marketDataUrl = marketDataUrl == null ? "http://localhost:8083" : marketDataUrl;
        maxQuantity = maxQuantity == null ? 1_000_000L : maxQuantity;
        maxNotional = maxNotional == null ? new BigDecimal("50000000") : maxNotional;
        stuckPendingAfter = stuckPendingAfter == null ? Duration.ofMinutes(2) : stuckPendingAfter;
    }
}
