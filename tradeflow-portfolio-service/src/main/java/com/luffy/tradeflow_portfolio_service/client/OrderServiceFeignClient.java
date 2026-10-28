package com.luffy.tradeflow_portfolio_service.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.UUID;

@FeignClient(name = "tradeflow-order-service", contextId = "portfolioOrders")
public interface OrderServiceFeignClient {

    @GetMapping("/api/orders/{id}")
    JsonNode order(@PathVariable("id") UUID id);

    @GetMapping("/api/orders")
    JsonNode recentOrders(@RequestParam("size") int size,
                          @RequestParam(value = "status", required = false) String status);

    /** Service-to-service endpoint; authenticated with a SERVICE token, not a user token. */
    @GetMapping("/internal/orders/status")
    List<OrderServiceClient.OrderStatusView> statuses(@RequestParam("ids") List<UUID> ids,
                                                      @RequestHeader("Authorization") String authorization);
}
