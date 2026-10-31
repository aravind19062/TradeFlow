package com.luffy.tradeflow_learning_service.progress;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "quiz_attempt_questions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class QuizAttemptQuestion {

    @Embeddable
    @Getter
    @EqualsAndHashCode
    @NoArgsConstructor(access = AccessLevel.PROTECTED)
    public static class Key implements Serializable {
        @Column(name = "attempt_id")
        private UUID attemptId;
        @Column(name = "position")
        private int position;

        Key(UUID attemptId, int position) {
            this.attemptId = attemptId;
            this.position = position;
        }
    }

    @EmbeddedId
    private Key id;
    @MapsId("attemptId")
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "attempt_id")
    private QuizAttempt attempt;
    @Column(name = "question_key", nullable = false)
    private String questionKey;
    @Column(name = "question_hash", nullable = false)
    private String questionHash;
    /** Option keys in the order shown to the user. */
    @Column(name = "option_order", nullable = false)
    private String optionOrder;
    @Column(name = "selected_keys")
    private String selectedKeys;
    private Boolean correct;

    QuizAttemptQuestion(QuizAttempt attempt, int position, String questionKey, String questionHash, String optionOrder) {
        this.id = new Key(attempt.getId(), position);
        this.attempt = attempt;
        this.questionKey = questionKey;
        this.questionHash = questionHash;
        this.optionOrder = optionOrder;
    }

    public int position() {
        return id.getPosition();
    }

    public List<String> optionOrderList() {
        return Arrays.asList(optionOrder.split(","));
    }

    public List<String> selected() {
        return selectedKeys == null || selectedKeys.isEmpty() ? List.of() : Arrays.asList(selectedKeys.split(","));
    }

    void answer(List<String> selected, boolean correct) {
        this.selectedKeys = String.join(",", selected.stream().sorted().toList());
        this.correct = correct;
    }

    public void record(List<String> selected, boolean isCorrect) {
        answer(selected, isCorrect);
    }
}
