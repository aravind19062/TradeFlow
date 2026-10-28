package com.luffy.tradeflow_order_service.config;

import com.luffy.tradeflow_order_service.domain.OrderStatus;
import com.luffy.tradeflow_order_service.repository.OrderRepository;
import com.luffy.tradeflow_order_service.saga.OrderSagaRepository;
import com.luffy.tradeflow_order_service.saga.SagaState;
import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;

/**
 * DOWN when a saga needs an operator (SETTLEMENT_FAILED / RELEASE_FAILED) or orders
 * sit in PENDING well beyond the reservation timeout (the recovery job itself is not
 * keeping up). Overdue in-flight sagas are reported as a detail.
 */
@Component("orderPipeline")
public class OrderPipelineHealthIndicator extends AbstractHealthIndicator {

    private final OrderRepository orderRepository;
    private final OrderSagaRepository sagaRepository;
    private final OrderProperties properties;
    private final Clock clock;

    public OrderPipelineHealthIndicator(OrderRepository orderRepository, OrderSagaRepository sagaRepository,
                                        OrderProperties properties, Clock clock) {
        this.orderRepository = orderRepository;
        this.sagaRepository = sagaRepository;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        long stuck = orderRepository.countByStatusAndUpdatedAtBefore(OrderStatus.PENDING,
                clock.instant().minus(properties.stuckPendingAfter()));
        long failed = sagaRepository.countByStateIn(SagaState.FAILED);
        long overdue = sagaRepository.countByStateInAndDeadlineAtBefore(SagaState.IN_FLIGHT,
                clock.instant().minus(Duration.ofMinutes(1)));
        builder.withDetail("stuckPendingOrders", stuck)
                .withDetail("failedSagas", failed)
                .withDetail("overdueSagas", overdue)
                .withDetail("threshold", properties.stuckPendingAfter().toString());
        if (stuck > 0 || failed > 0) {
            builder.down();
        } else {
            builder.up();
        }
    }
}
