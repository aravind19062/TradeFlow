package com.luffy.tradeflow_learning_service.progress;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One sitting of a module quiz or the final assessment. Questions and option order are fixed when
 * the attempt starts; grading happens only on the server, and correct answers are revealed only in
 * the result of a submitted attempt.
 */
@Entity
@Table(name = "quiz_attempts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class QuizAttempt {

    public static final String FINAL = "final";

    @Id
    private UUID id;
    @Column(name = "user_id", nullable = false)
    private long userId;
    @Column(name = "quiz_key", nullable = false)
    private String quizKey;
    @Column(name = "content_version", nullable = false)
    private int contentVersion;
    @Column(name = "policy_version")
    private Integer policyVersion;
    @Column(name = "pass_mark_pct", nullable = false)
    private int passMarkPct;
    @Column(name = "started_at", nullable = false)
    private Instant startedAt;
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
    @Column(name = "submitted_at")
    private Instant submittedAt;
    @Column(name = "question_count", nullable = false)
    private int questionCount;
    @Column(name = "correct_count")
    private Integer correctCount;
    @Column(name = "score_pct")
    private Integer scorePct;
    private Boolean passed;
    @Version
    private long version;

    @OneToMany(mappedBy = "attempt", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position ASC")
    private List<QuizAttemptQuestion> questions = new ArrayList<>();

    public QuizAttempt(long userId, String quizKey, int contentVersion, Integer policyVersion, int passMarkPct,
                       Instant startedAt, Instant expiresAt) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.quizKey = quizKey;
        this.contentVersion = contentVersion;
        this.policyVersion = policyVersion;
        this.passMarkPct = passMarkPct;
        this.startedAt = startedAt;
        this.expiresAt = expiresAt;
    }

    public void addQuestion(String key, String hash, List<String> optionOrder) {
        questions.add(new QuizAttemptQuestion(this, questions.size(), key, hash, String.join(",", optionOrder)));
        questionCount = questions.size();
    }

    public boolean isOpen(Instant now) {
        return submittedAt == null && now.isBefore(expiresAt);
    }

    public void grade(int correct, Instant now) {
        this.correctCount = correct;
        this.scorePct = questionCount == 0 ? 0 : (int) Math.floor(100.0 * correct / questionCount);
        this.passed = scorePct >= passMarkPct;
        this.submittedAt = now;
    }
}
