package com.luffy.tradeflow_portfolio_service.client;

import com.luffy.tradeflow.common.web.ApiException;
import com.luffy.tradeflow_portfolio_service.config.PortfolioProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

/**
 * Reads orders from order-service with the caller's own JWT (token relay), so
 * order-service enforces ownership: another user's order comes back as 404.
 */
@Component
public class OrderServiceClient {

    private final RestClient restClient;

    public OrderServiceClient(RestClient.Builder builder, PortfolioProperties properties) {
        this.restClient = builder.baseUrl(properties.orderServiceUrl()).build();
    }

    public JsonNode order(UUID orderId) {
        try {
            return restClient.get().uri("/api/orders/{id}", orderId).retrieve()
                    .onStatus(s -> s.value() == HttpStatus.NOT_FOUND.value(), (req, res) -> {
                        throw ApiException.notFound("Order " + orderId);
                    })
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            throw ApiException.dependencyUnavailable("order-service", e.getMessage());
        }
    }

    public JsonNode recentOrders(String status, int size) {
        try {
            return restClient.get()
                    .uri(b -> {
                        b.path("/api/orders").queryParam("size", size);
                        if (status != null && !status.isBlank()) {
                            b.queryParam("status", status);
                        }
                        return b.build();
                    })
                    .retrieve().body(JsonNode.class);
        } catch (RestClientException e) {
            throw ApiException.dependencyUnavailable("order-service", e.getMessage());
        }
    }
}
