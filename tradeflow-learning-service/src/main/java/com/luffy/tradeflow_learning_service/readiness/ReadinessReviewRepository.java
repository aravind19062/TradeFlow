package com.luffy.tradeflow_learning_service.readiness;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReadinessReviewRepository extends JpaRepository<ReadinessReview, Long> {

    List<ReadinessReview> findByUserIdOrderByDecidedAtDesc(long userId);
}
