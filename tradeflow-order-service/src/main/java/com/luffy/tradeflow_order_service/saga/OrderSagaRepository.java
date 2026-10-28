package com.luffy.tradeflow_order_service.saga;

import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface OrderSagaRepository extends JpaRepository<OrderSaga, UUID> {

    List<OrderSaga> findByStateInAndDeadlineAtBeforeOrderByDeadlineAtAsc(Collection<SagaState> states, Instant now,
                                                                         Limit limit);

    long countByStateIn(Collection<SagaState> states);

    long countByStateInAndDeadlineAtBefore(Collection<SagaState> states, Instant before);

    Page<OrderSaga> findByState(SagaState state, Pageable pageable);
}
