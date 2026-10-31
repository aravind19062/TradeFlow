package com.luffy.tradeflow_learning_service.progress;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LessonProgressRepository extends JpaRepository<LessonProgress, LessonProgress.Key> {

    List<LessonProgress> findByIdUserId(long userId);
}
