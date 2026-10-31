package com.luffy.tradeflow_learning_service.readiness;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PositionSizingScenarioRepository extends JpaRepository<PositionSizingScenario, UUID> {

    Optional<PositionSizingScenario> findFirstByUserIdOrderByIssuedAtDesc(long userId);
}
