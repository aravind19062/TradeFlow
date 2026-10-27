package com.luffy.tradeflow_order_service.repository;

import com.luffy.tradeflow.common.events.Events.OrderType;
import com.luffy.tradeflow_order_service.domain.Order;
import com.luffy.tradeflow_order_service.domain.OrderStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<Order, UUID>, JpaSpecificationExecutor<Order> {

    Optional<Order> findByIdAndUserId(UUID id, long userId);

    Optional<Order> findByUserIdAndClientOrderId(long userId, String clientOrderId);

    List<Order> findBySymbolAndStatusAndType(String symbol, OrderStatus status, OrderType type);

    long countByStatusAndUpdatedAtBefore(OrderStatus status, Instant before);
}
