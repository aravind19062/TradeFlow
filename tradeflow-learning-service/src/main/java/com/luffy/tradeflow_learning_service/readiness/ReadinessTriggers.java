package com.luffy.tradeflow_learning_service.readiness;

import com.luffy.tradeflow_learning_service.academy.AcademyService;
import com.luffy.tradeflow_learning_service.academy.QuizService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Clock;

/**
 * Re-evaluates readiness after Academy progress commits, and periodically for time-based
 * changes (qualification expiry, abandoned final-assessment attempts). Eligibility checks also
 * re-evaluate on every call, so access never depends on this job having run.
 */
@Component
public class ReadinessTriggers {

    private static final Logger log = LoggerFactory.getLogger(ReadinessTriggers.class);

    private final ReadinessService readiness;
    private final ReadinessStatusRepository statuses;
    private final Clock clock;

    public ReadinessTriggers(ReadinessService readiness, ReadinessStatusRepository statuses, Clock clock) {
        this.readiness = readiness;
        this.statuses = statuses;
        this.clock = clock;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onLesson(AcademyService.LessonCompleted e) {
        readiness.reconcile(e.userId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onQuiz(QuizService.QuizSubmitted e) {
        readiness.reconcile(e.userId());
    }

    @Scheduled(fixedDelayString = "${tradeflow.readiness.sweep-interval-ms:600000}", initialDelay = 60_000)
    public void sweep() {
        var due = new java.util.ArrayList<>(statuses.findByStateAndExpiresAtBefore(ReadinessState.QUALIFIED, clock.instant()));
        due.addAll(statuses.findByState(ReadinessState.ASSESSMENT_PENDING));
        for (ReadinessStatus s : due) {
            try {
                readiness.reconcile(s.getUserId());
            } catch (RuntimeException ex) {
                log.warn("Readiness sweep failed for user {}: {}", s.getUserId(), ex.getMessage());
            }
        }
    }
}
