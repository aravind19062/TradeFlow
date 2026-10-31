package com.luffy.tradeflow_learning_service.policy;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;

public interface ReadinessPolicyRepository extends JpaRepository<ReadinessPolicy, Integer> {

    Optional<ReadinessPolicy> findFirstByEffectiveFromLessThanEqualOrderByVersionDesc(Instant now);

    Optional<ReadinessPolicy> findFirstByOrderByVersionDesc();
}
