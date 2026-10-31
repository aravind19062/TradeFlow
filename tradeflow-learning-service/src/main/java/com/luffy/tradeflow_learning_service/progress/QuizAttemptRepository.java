package com.luffy.tradeflow_learning_service.progress;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface QuizAttemptRepository extends JpaRepository<QuizAttempt, UUID> {

    List<QuizAttempt> findByUserIdOrderByStartedAtDesc(long userId);

    List<QuizAttempt> findByUserIdAndQuizKeyOrderByStartedAtDesc(long userId, String quizKey);

    Optional<QuizAttempt> findByIdAndUserId(UUID id, long userId);
}
