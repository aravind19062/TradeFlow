package com.luffy.tradeflow_order_service.web;

import com.luffy.tradeflow_order_service.domain.Order;
import com.luffy.tradeflow_order_service.repository.OrderRepository;
import com.luffy.tradeflow_order_service.saga.OrderSaga;
import com.luffy.tradeflow_order_service.saga.OrderSagaRepository;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Service-to-service API (role SERVICE only; never routed by the gateway). Used by
 * portfolio-service's cross-service reconciliation.
 */
@Hidden
@RestController
public class InternalOrderController {

    public record OrderStatusView(UUID orderId, String status, String sagaState) {
    }

    private final OrderRepository orderRepository;
    private final OrderSagaRepository sagaRepository;

    public InternalOrderController(OrderRepository orderRepository, OrderSagaRepository sagaRepository) {
        this.orderRepository = orderRepository;
        this.sagaRepository = sagaRepository;
    }

    @GetMapping("/internal/orders/status")
    @Transactional(readOnly = true)
    public List<OrderStatusView> statuses(@RequestParam List<UUID> ids) {
        List<UUID> capped = ids.stream().distinct().limit(500).toList();
        Map<UUID, OrderSaga> sagas = sagaRepository.findAllById(capped).stream()
                .collect(Collectors.toMap(OrderSaga::getOrderId, Function.identity()));
        return orderRepository.findAllById(capped).stream()
                .map((Order o) -> new OrderStatusView(o.getId(), o.getStatus().name(),
                        sagas.containsKey(o.getId()) ? sagas.get(o.getId()).getState().name() : null))
                .toList();
    }
}
