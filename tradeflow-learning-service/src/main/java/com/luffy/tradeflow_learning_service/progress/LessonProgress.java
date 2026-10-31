package com.luffy.tradeflow_learning_service.progress;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;

/** A lesson the user has read (marked complete), with the content version and text hash at the time. */
@Entity
@Table(name = "lesson_progress")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LessonProgress {

    @Embeddable
    @Getter
    @EqualsAndHashCode
    @NoArgsConstructor(access = AccessLevel.PROTECTED)
    public static class Key implements Serializable {
        @Column(name = "user_id")
        private long userId;
        @Column(name = "module_slug")
        private String moduleSlug;
        @Column(name = "lesson_slug")
        private String lessonSlug;

        public Key(long userId, String moduleSlug, String lessonSlug) {
            this.userId = userId;
            this.moduleSlug = moduleSlug;
            this.lessonSlug = lessonSlug;
        }
    }

    @EmbeddedId
    private Key id;
    @Column(name = "content_version", nullable = false)
    private int contentVersion;
    @Column(name = "lesson_hash", nullable = false)
    private String lessonHash;
    @Column(name = "completed_at", nullable = false)
    private Instant completedAt;

    public LessonProgress(Key id, int contentVersion, String lessonHash, Instant completedAt) {
        this.id = id;
        this.contentVersion = contentVersion;
        this.lessonHash = lessonHash;
        this.completedAt = completedAt;
    }
}
