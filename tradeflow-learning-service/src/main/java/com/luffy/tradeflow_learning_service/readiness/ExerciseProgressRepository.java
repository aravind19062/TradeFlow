package com.luffy.tradeflow_learning_service.readiness;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExerciseProgressRepository extends JpaRepository<ExerciseProgress, ExerciseProgress.Key> {

    List<ExerciseProgress> findByIdUserId(long userId);
}
