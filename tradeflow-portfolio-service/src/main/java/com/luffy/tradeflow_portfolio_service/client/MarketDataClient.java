package com.luffy.tradeflow_portfolio_service.client;

import com.luffy.tradeflow.common.web.ApiException;
import com.luffy.tradeflow_portfolio_service.config.PortfolioProperties;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** Reads simulated quotes and history from market-data-service (public endpoints). */
@Component
public class MarketDataClient {

    private final RestClient restClient;

    public MarketDataClient(RestClient.Builder builder, PortfolioProperties properties) {
        this.restClient = builder.baseUrl(properties.marketDataUrl()).build();
    }

    public List<Quote> quotes(Collection<String> symbols) {
        if (symbols.isEmpty()) {
            return List.of();
        }
        try {
            List<Quote> quotes = restClient.get()
                    .uri(b -> b.path("/api/market/quotes").queryParam("symbols", String.join(",", symbols)).build())
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<Quote>>() { });
            return quotes == null ? List.of() : quotes;
        } catch (RestClientException e) {
            throw ApiException.dependencyUnavailable("market-data-service", e.getMessage());
        }
    }

    public Map<String, History> histories(Collection<String> symbols, Instant since, int limit) {
        if (symbols.isEmpty()) {
            return Map.of();
        }
        try {
            Map<String, History> result = restClient.get()
                    .uri(b -> b.path("/api/market/history")
                            .queryParam("symbols", String.join(",", symbols))
                            .queryParam("since", since.toString())
                            .queryParam("limit", limit)
                            .build())
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, History>>() { });
            return result == null ? Map.of() : result;
        } catch (RestClientException e) {
            throw ApiException.dependencyUnavailable("market-data-service", e.getMessage());
        }
    }

    public List<Quote> watchlist() {
        try {
            List<Quote> quotes = restClient.get().uri("/api/watchlist").retrieve()
                    .body(new ParameterizedTypeReference<List<Quote>>() { });
            return quotes == null ? List.of() : quotes;
        } catch (RestClientException e) {
            throw ApiException.dependencyUnavailable("market-data-service", e.getMessage());
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
}
