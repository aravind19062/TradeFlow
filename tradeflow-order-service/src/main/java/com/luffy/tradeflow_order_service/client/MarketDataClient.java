package com.luffy.tradeflow_order_service.client;

import com.luffy.tradeflow.common.web.ApiException;
import com.luffy.tradeflow_order_service.config.OrderProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.time.Instant;

/** Synchronous quote lookup used when an order is accepted. */
@Component
public class MarketDataClient {

    private final RestClient restClient;

    public MarketDataClient(RestClient.Builder builder, OrderProperties properties) {
        this.restClient = builder.baseUrl(properties.marketDataUrl()).build();
    }

    public Quote quote(String symbol) {
        try {
            Quote quote = restClient.get().uri("/api/market/quotes/{symbol}", symbol)
                    .retrieve()
                    .onStatus(status -> status.value() == HttpStatus.NOT_FOUND.value(), (request, response) -> {
                        throw ApiException.unprocessable("UNKNOWN_SYMBOL", "Unknown symbol " + symbol);
                    })
                    .body(Quote.class);
            if (quote == null || quote.price() == null) {
                throw ApiException.dependencyUnavailable("market-data-service", "empty quote for " + symbol);
            }
            return quote;
        } catch (RestClientException e) {
            throw ApiException.dependencyUnavailable("market-data-service", e.getMessage());
        }
    }

    public record Quote(String symbol, BigDecimal price, long tick, Instant asOf, String source) {
    }
}
