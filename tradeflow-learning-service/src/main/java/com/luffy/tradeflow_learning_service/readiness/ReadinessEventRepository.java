package com.luffy.tradeflow_learning_service.readiness;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ReadinessEventRepository extends JpaRepository<ReadinessEvent, Long> {

    List<ReadinessEvent> findByUserIdOrderBySeqAsc(long userId);

    Optional<ReadinessEvent> findFirstByUserIdOrderBySeqDesc(long userId);
}
