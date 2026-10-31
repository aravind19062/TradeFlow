package com.luffy.tradeflow_learning_service.readiness;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RiskAcknowledgementRepository extends JpaRepository<RiskAcknowledgement, Long> {

    Optional<RiskAcknowledgement> findFirstByUserIdOrderByAcceptedAtDesc(long userId);
}
