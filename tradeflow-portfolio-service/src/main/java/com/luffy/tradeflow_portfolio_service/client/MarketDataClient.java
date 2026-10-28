package com.luffy.tradeflow_portfolio_service.client;

import com.luffy.tradeflow.common.web.RemoteCallErrors;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Simulated quotes, history, watchlist and alerts from market-data-service (OpenFeign). */
@Component
public class MarketDataClient {

    private final MarketDataFeignClient feign;

    public MarketDataClient(MarketDataFeignClient feign) {
        this.feign = feign;
    }

    public List<Quote> quotes(Collection<String> symbols) {
        return symbols.isEmpty() ? List.of() : call(() -> feign.quotes(String.join(",", symbols)));
    }

    public Map<String, History> histories(Collection<String> symbols, Instant since, int limit) {
        return symbols.isEmpty() ? Map.of() : call(() -> feign.histories(String.join(",", symbols), since.toString(), limit));
    }

    /** The caller's own watchlist (their JWT is relayed). */
    public List<Quote> watchlist() {
        return call(feign::watchlist);
    }

    /** The caller's own price alerts (their JWT is relayed). */
    public List<Alert> alerts(String status) {
        return call(() -> feign.alerts(status));
    }

    private static <T> T call(Supplier<T> action) {
        try {
            T result = action.get();
            return result;
        } catch (RuntimeException e) {
            throw RemoteCallErrors.unavailable("market-data-service", e);
        }
    }

    public record Quote(String symbol, String name, String sector, String assetClass, String currency,
                        BigDecimal price, BigDecimal previousPrice, BigDecimal change, BigDecimal changeRatio,
                        long tick, Instant asOf, String source) {
    }

    public record PricePoint(long tick, BigDecimal price, Instant asOf) {
    }

    public record History(String symbol, String source, List<PricePoint> points) {
    }

    public record Alert(String id, String symbol, String direction, BigDecimal threshold, String status, String note,
                        Instant createdAt, Instant triggeredAt, BigDecimal triggeredPrice) {
    }
}
