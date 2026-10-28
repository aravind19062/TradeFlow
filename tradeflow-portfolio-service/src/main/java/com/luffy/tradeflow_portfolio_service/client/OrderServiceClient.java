package com.luffy.tradeflow_portfolio_service.client;

import com.luffy.tradeflow.common.web.ApiException;
import com.luffy.tradeflow.common.web.RemoteCallErrors;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Reads orders from order-service (OpenFeign). User-facing calls relay the caller's
 * JWT, so order-service enforces ownership: another user's order comes back as 404.
 */
@Component
public class OrderServiceClient {

    private final OrderServiceFeignClient feign;

    public OrderServiceClient(OrderServiceFeignClient feign) {
        this.feign = feign;
    }

    public JsonNode order(UUID orderId) {
        try {
            return feign.order(orderId);
        } catch (RuntimeException e) {
            if (RemoteCallErrors.remoteStatus(e) == 404) {
                throw ApiException.notFound("Order " + orderId);
            }
            throw RemoteCallErrors.unavailable("order-service", e);
        }
    }

    public JsonNode recentOrders(String status, int size) {
        try {
            return feign.recentOrders(size, status);
        } catch (RuntimeException e) {
            throw RemoteCallErrors.unavailable("order-service", e);
        }
    }

    public record OrderStatusView(UUID orderId, String status, String sagaState) {
    }

    /** Internal, service-authenticated status lookup (no end-user token involved). */
    public List<OrderStatusView> statuses(Collection<UUID> ids, String serviceToken) {
        if (ids.isEmpty()) {
            return List.of();
        }
        try {
            List<OrderStatusView> result = feign.statuses(List.copyOf(ids), "Bearer " + serviceToken);
            return result == null ? List.of() : result;
        } catch (RuntimeException e) {
            throw RemoteCallErrors.unavailable("order-service", e);
        }
    }
}
