package com.luffy.tradeflow_learning_service.academy;

import com.luffy.tradeflow.common.web.ApiException;
import com.luffy.tradeflow_learning_service.policy.PolicyService;
import com.luffy.tradeflow_learning_service.policy.ReadinessPolicy;
import com.luffy.tradeflow_learning_service.progress.QuizAttempt;
import com.luffy.tradeflow_learning_service.progress.QuizAttemptQuestion;
import com.luffy.tradeflow_learning_service.progress.QuizAttemptRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Quizzes: questions are drawn and shuffled on the server, options are shuffled, attempts are
 * time-limited, and correct answers never leave the server before an attempt is submitted. Used by
 * module quizzes here and by the final readiness assessment (readiness package), which adds its
 * own eligibility, cooldown and attempt rules.
 */
@Service
public class QuizService {

    /** A module quiz or the final assessment changed state (readiness re-evaluates on this). */
    public record QuizSubmitted(long userId, String quizKey, boolean passed) {
    }

    public record ServedOption(String key, String text) {
    }

    public record ServedQuestion(String key, int position, String prompt, boolean multi, List<ServedOption> options) {
    }

    public record AttemptView(UUID id, String quizKey, Instant startedAt, Instant expiresAt, int passMarkPct,
                              List<ServedQuestion> questions) {
    }

    public record Answer(String questionKey, List<String> optionKeys) {
    }

    public record GradedOption(String key, String text, boolean correct, boolean selected) {
    }

    public record GradedQuestion(String key, String prompt, boolean multi, List<GradedOption> options, boolean correct,
                                 String explanation) {
    }

    public record ResultView(UUID id, String quizKey, Instant startedAt, Instant submittedAt, int questionCount,
                             int correctCount, int scorePct, int passMarkPct, boolean passed, Integer policyVersion,
                             List<GradedQuestion> questions) {
    }

    private final QuizAttemptRepository attempts;
    private final ContentCatalog catalog;
    private final PolicyService policies;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public QuizService(QuizAttemptRepository attempts, ContentCatalog catalog, PolicyService policies,
                       ApplicationEventPublisher events, Clock clock) {
        this.attempts = attempts;
        this.catalog = catalog;
        this.policies = policies;
        this.events = events;
        this.clock = clock;
    }

    /** Starts (or resumes the still-open) attempt. Callers check eligibility first. */
    @Transactional
    public AttemptView start(long userId, String quizKey, int passMarkPct, Integer policyVersion) {
        Instant now = clock.instant();
        Optional<QuizAttempt> open = attempts.findByUserIdAndQuizKeyOrderByStartedAtDesc(userId, quizKey).stream()
                .filter(a -> a.isOpen(now)).findFirst();
        if (open.isPresent()) {
            return view(open.get());
        }
        Catalog.Content content = catalog.content();
        List<Catalog.Question> bank;
        Catalog.QuizSpec spec;
        if (QuizAttempt.FINAL.equals(quizKey)) {
            bank = content.finalQuestions();
            spec = content.finalAssessment();
        } else {
            Catalog.Module module = catalog.module(quizKey);
            bank = module.questions();
            spec = module.quiz();
        }
        List<Catalog.Question> drawn = new ArrayList<>(bank);
        Collections.shuffle(drawn, random);
        drawn = drawn.subList(0, spec.questionsServed());
        QuizAttempt attempt = new QuizAttempt(userId, quizKey, content.version(), policyVersion, passMarkPct, now,
                now.plus(Duration.ofMinutes(spec.timeLimitMinutes())));
        for (Catalog.Question q : drawn) {
            List<String> order = new ArrayList<>(q.options().stream().map(Catalog.Option::key).toList());
            Collections.shuffle(order, random);
            attempt.addQuestion(q.key(), q.hash(), order);
        }
        return view(attempts.save(attempt));
    }

    @Transactional
    public ResultView submit(long userId, UUID attemptId, List<Answer> answers) {
        QuizAttempt attempt = attempts.findByIdAndUserId(attemptId, userId)
                .orElseThrow(() -> ApiException.notFound("Attempt " + attemptId));
        if (attempt.getSubmittedAt() != null) {
            throw ApiException.conflict("ATTEMPT_ALREADY_SUBMITTED", "This attempt was already submitted");
        }
        Instant now = clock.instant();
        if (!now.isBefore(attempt.getExpiresAt())) {
            throw new ApiException(HttpStatus.CONFLICT, "ATTEMPT_EXPIRED", "The time limit for this attempt has passed");
        }
        Map<String, Answer> byKey = answers == null ? Map.of()
                : answers.stream().filter(a -> a.questionKey() != null)
                        .collect(Collectors.toMap(Answer::questionKey, Function.identity(), (a, b) -> b));
        int correct = 0;
        for (QuizAttemptQuestion q : attempt.getQuestions()) {
            Catalog.Question question = question(attempt.getQuizKey(), q.getQuestionKey());
            Set<String> allowed = new HashSet<>(q.optionOrderList());
            List<String> selected = Optional.ofNullable(byKey.get(q.getQuestionKey())).map(Answer::optionKeys)
                    .orElse(List.of()).stream().filter(allowed::contains).distinct().toList();
            boolean ok = new HashSet<>(selected).equals(question.correctKeys());
            q.record(selected, ok);
            if (ok) {
                correct++;
            }
        }
        attempt.grade(correct, now);
        events.publishEvent(new QuizSubmitted(userId, attempt.getQuizKey(), Boolean.TRUE.equals(attempt.getPassed())));
        return result(attempt);
    }

    @Transactional(readOnly = true)
    public Object get(long userId, UUID attemptId) {
        QuizAttempt attempt = attempts.findByIdAndUserId(attemptId, userId)
                .orElseThrow(() -> ApiException.notFound("Attempt " + attemptId));
        return attempt.getSubmittedAt() == null ? view(attempt) : result(attempt);
    }

    /** Best (highest-scoring) submitted attempt for a quiz, if any. */
    @Transactional(readOnly = true)
    public Optional<QuizAttempt> best(long userId, String quizKey) {
        return attempts.findByUserIdAndQuizKeyOrderByStartedAtDesc(userId, quizKey).stream()
                .filter(a -> a.getSubmittedAt() != null)
                .max(java.util.Comparator.comparing(QuizAttempt::getScorePct));
    }

    @Transactional(readOnly = true)
    public boolean passed(long userId, String quizKey) {
        return attempts.findByUserIdAndQuizKeyOrderByStartedAtDesc(userId, quizKey).stream()
                .anyMatch(a -> Boolean.TRUE.equals(a.getPassed()));
    }

    public ReadinessPolicy policy() {
        return policies.active();
    }

    private AttemptView view(QuizAttempt attempt) {
        List<ServedQuestion> served = attempt.getQuestions().stream().map(q -> {
            Catalog.Question question = question(attempt.getQuizKey(), q.getQuestionKey());
            Map<String, Catalog.Option> options = question.options().stream()
                    .collect(Collectors.toMap(Catalog.Option::key, Function.identity()));
            // Text only: the "correct" flags stay on the server.
            return new ServedQuestion(question.key(), q.position(), question.prompt(), question.multi(),
                    q.optionOrderList().stream().map(k -> new ServedOption(k, options.get(k).text())).toList());
        }).toList();
        return new AttemptView(attempt.getId(), attempt.getQuizKey(), attempt.getStartedAt(), attempt.getExpiresAt(),
                attempt.getPassMarkPct(), served);
    }

    private ResultView result(QuizAttempt attempt) {
        List<GradedQuestion> graded = attempt.getQuestions().stream().map(q -> {
            Catalog.Question question = question(attempt.getQuizKey(), q.getQuestionKey());
            Map<String, Catalog.Option> options = question.options().stream()
                    .collect(Collectors.toMap(Catalog.Option::key, Function.identity()));
            Set<String> selected = new HashSet<>(q.selected());
            return new GradedQuestion(question.key(), question.prompt(), question.multi(),
                    q.optionOrderList().stream().map(k -> new GradedOption(k, options.get(k).text(),
                            options.get(k).correct(), selected.contains(k))).toList(),
                    Boolean.TRUE.equals(q.getCorrect()), question.explanation());
        }).toList();
        return new ResultView(attempt.getId(), attempt.getQuizKey(), attempt.getStartedAt(), attempt.getSubmittedAt(),
                attempt.getQuestionCount(), attempt.getCorrectCount(), attempt.getScorePct(), attempt.getPassMarkPct(),
                Boolean.TRUE.equals(attempt.getPassed()), attempt.getPolicyVersion(), graded);
    }

    private Catalog.Question question(String quizKey, String questionKey) {
        List<Catalog.Question> bank = QuizAttempt.FINAL.equals(quizKey)
                ? catalog.content().finalQuestions() : catalog.module(quizKey).questions();
        return bank.stream().filter(q -> q.key().equals(questionKey)).findFirst()
                .orElseThrow(() -> new IllegalStateException("Question " + questionKey + " is no longer in the catalog"));
    }
}
