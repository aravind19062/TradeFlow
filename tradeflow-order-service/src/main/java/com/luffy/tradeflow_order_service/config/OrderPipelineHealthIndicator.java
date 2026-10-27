package com.luffy.tradeflow_order_service.config;

import com.luffy.tradeflow_order_service.domain.OrderStatus;
import com.luffy.tradeflow_order_service.repository.OrderRepository;
import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * DOWN when orders sit in PENDING longer than expected, which means portfolio-service
 * is not answering reservations (Kafka down, consumer failing, events in a DLT).
 */
@Component("orderPipeline")
public class OrderPipelineHealthIndicator extends AbstractHealthIndicator {

    private final OrderRepository orderRepository;
    private final OrderProperties properties;
    private final Clock clock;

    public OrderPipelineHealthIndicator(OrderRepository orderRepository, OrderProperties properties, Clock clock) {
        this.orderRepository = orderRepository;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        long stuck = orderRepository.countByStatusAndUpdatedAtBefore(OrderStatus.PENDING,
                clock.instant().minus(properties.stuckPendingAfter()));
        builder.withDetail("stuckPendingOrders", stuck)
                .withDetail("threshold", properties.stuckPendingAfter().toString());
        if (stuck > 0) {
            builder.down();
        } else {
            builder.up();
        }
    }
}
