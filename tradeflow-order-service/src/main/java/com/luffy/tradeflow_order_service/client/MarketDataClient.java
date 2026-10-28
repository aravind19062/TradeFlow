package com.luffy.tradeflow_order_service.client;

import com.luffy.tradeflow.common.web.ApiException;
import com.luffy.tradeflow.common.web.RemoteCallErrors;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Synchronous quote lookup used when an order is accepted (OpenFeign + Eureka +
 * LoadBalancer, circuit breaker, one retry on another instance for this GET).
 */
@Component
public class MarketDataClient {

    private final MarketDataFeignClient feign;

    public MarketDataClient(MarketDataFeignClient feign) {
        this.feign = feign;
    }

    public Quote quote(String symbol) {
        Quote quote;
        try {
            quote = feign.quote(symbol);
        } catch (RuntimeException e) {
            if (RemoteCallErrors.remoteStatus(e) == 404) {
                throw ApiException.unprocessable("UNKNOWN_SYMBOL", "Unknown symbol " + symbol);
            }
            throw RemoteCallErrors.unavailable("market-data-service", e);
        }
        if (quote == null || quote.price() == null) {
            throw ApiException.dependencyUnavailable("market-data-service", "empty quote for " + symbol);
        }
        return quote;
    }

    public record Quote(String symbol, BigDecimal price, long tick, Instant asOf, String source) {
    }
}
