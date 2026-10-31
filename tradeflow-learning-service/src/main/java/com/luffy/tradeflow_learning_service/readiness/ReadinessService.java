package com.luffy.tradeflow_learning_service.readiness;

import com.luffy.tradeflow.common.events.Events;
import com.luffy.tradeflow.common.events.Topics;
import com.luffy.tradeflow.common.outbox.OutboxWriter;
import com.luffy.tradeflow.common.web.ApiException;
import com.luffy.tradeflow_learning_service.academy.AcademyService;
import com.luffy.tradeflow_learning_service.academy.Catalog;
import com.luffy.tradeflow_learning_service.academy.ContentCatalog;
import com.luffy.tradeflow_learning_service.academy.QuizService;
import com.luffy.tradeflow_learning_service.policy.PolicyService;
import com.luffy.tradeflow_learning_service.policy.ReadinessPolicy;
import com.luffy.tradeflow_learning_service.policy.ReadinessPolicyRepository;
import com.luffy.tradeflow_learning_service.progress.QuizAttempt;
import com.luffy.tradeflow_learning_service.progress.QuizAttemptRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The only authority on readiness. Every change goes through {@link #transition}, which checks the
 * state machine, writes a hash-chained audit event and publishes readiness.changed, all in the
 * caller's transaction. {@link #reconcile} derives SYSTEM transitions from evidence (lessons, quizzes,
 * exercises, the final assessment, the acknowledgement, expiry, policy changes). Demo profit, trade
 * counts and volume are never evidence.
 */
@Service
public class ReadinessService {

    public static final String ACK_PHRASE = "I UNDERSTAND THE RISKS";
    static final String GENESIS = "GENESIS";

    private final ReadinessStatusRepository statuses;
    private final ReadinessEventRepository events;
    private final RiskAcknowledgementRepository acknowledgements;
    private final ExerciseProgressRepository exercises;
    private final ReadinessReviewRepository reviews;
    private final QuizAttemptRepository attempts;
    private final ReadinessPolicyRepository policyRepository;
    private final PolicyService policies;
    private final AcademyService academy;
    private final QuizService quizzes;
    private final ContentCatalog catalog;
    private final AcknowledgementText ackText;
    private final OutboxWriter outbox;
    private final Clock clock;

    public ReadinessService(ReadinessStatusRepository statuses, ReadinessEventRepository events,
                            RiskAcknowledgementRepository acknowledgements, ExerciseProgressRepository exercises,
                            ReadinessReviewRepository reviews, QuizAttemptRepository attempts,
                            ReadinessPolicyRepository policyRepository, PolicyService policies, AcademyService academy,
                            QuizService quizzes, ContentCatalog catalog, AcknowledgementText ackText,
                            OutboxWriter outbox, Clock clock) {
        this.statuses = statuses;
        this.events = events;
        this.acknowledgements = acknowledgements;
        this.exercises = exercises;
        this.reviews = reviews;
        this.attempts = attempts;
        this.policyRepository = policyRepository;
        this.policies = policies;
        this.academy = academy;
        this.quizzes = quizzes;
        this.catalog = catalog;
        this.ackText = ackText;
        this.outbox = outbox;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ views

    public record FinalAssessmentView(boolean available, int failedAttempts, int maxFailedAttempts,
                                      Instant retakeAvailableAt, Integer lastScorePct, boolean passed,
                                      int passMarkPct, int questions, int timeLimitMinutes) {
    }

    public record ExerciseView(String slug, String title, String module, String description, boolean required,
                               Instant startedAt, Instant completedAt) {
    }

    public record Checklist(int modulesCompleted, int moduleCount, boolean modulesDone, List<String> remediationModules,
                            List<ExerciseView> exercises, boolean exercisesDone, FinalAssessmentView finalAssessment,
                            boolean acknowledged) {
    }

    public record StatusView(ReadinessState state, String reason, int policyVersion, Instant qualifiedAt,
                             Instant expiresAt, Instant selfExcludedUntil, boolean eligibleForLiveTrading,
                             String nextStep, Checklist checklist, PolicySummary policy) {
    }

    public record PolicySummary(int version, int modulePassMarkPct, int finalPassMarkPct, int finalRetakeCooldownHours,
                                int maxFailedFinalAttempts, int qualificationValidityDays) {
        static PolicySummary of(ReadinessPolicy p) {
            return new PolicySummary(p.getVersion(), p.getModulePassMarkPct(), p.getFinalPassMarkPct(),
                    p.getFinalRetakeCooldownHours(), p.getMaxFailedFinalAttempts(), p.getQualificationValidityDays());
        }
    }

    /** Eligibility as used by live-trading-service. Computed fresh on every call (fail closed). */
    public record Eligibility(long userId, ReadinessState state, boolean eligible, List<String> reasons,
                              Integer qualifiedPolicyVersion, Instant qualifiedAt, Instant expiresAt,
                              Instant selfExcludedUntil, Instant checkedAt) {
    }

    // ------------------------------------------------------------------ user operations

    @Transactional
    public StatusView status(long userId) {
        ReadinessStatus status = reconcile(userId);
        return view(status, policies.active());
    }

    @Transactional
    public QuizService.AttemptView startFinalAssessment(long userId) {
        ReadinessStatus status = reconcile(userId);
        if (status.getState() != ReadinessState.ASSESSMENT_PENDING) {
            throw ApiException.conflict("ASSESSMENT_NOT_AVAILABLE",
                    "The final assessment opens once all modules and practical exercises are complete");
        }
        ReadinessPolicy policy = policies.active();
        FinalAssessmentView fin = finalView(status, policy);
        if (fin.retakeAvailableAt() != null && clock.instant().isBefore(fin.retakeAvailableAt())) {
            long wait = Duration.between(clock.instant(), fin.retakeAvailableAt()).toSeconds();
            throw ApiException.tooManyRequests("RETAKE_COOLDOWN", "You can retake the assessment after "
                    + fin.retakeAvailableAt() + " (a " + policy.getFinalRetakeCooldownHours() + "-hour pause)", wait);
        }
        return quizzes.start(userId, QuizAttempt.FINAL, policy.getFinalPassMarkPct(), policy.getVersion());
    }

    public record AcknowledgementView(int textVersion, String text, String textSha256, String phrase) {
    }

    public AcknowledgementView acknowledgementText() {
        int version = policies.active().getAcknowledgementVersion();
        return new AcknowledgementView(version, ackText.text(version), ackText.sha256(version), ACK_PHRASE);
    }

    @Transactional
    public StatusView acknowledge(long userId, int textVersion, String typedPhrase, String clientIp, String userAgent) {
        ReadinessStatus status = reconcile(userId);
        if (status.getState() != ReadinessState.ACKNOWLEDGEMENT_PENDING) {
            throw ApiException.conflict("ACKNOWLEDGEMENT_NOT_EXPECTED",
                    "The risk acknowledgement comes after passing the final assessment");
        }
        ReadinessPolicy policy = policies.active();
        if (textVersion != policy.getAcknowledgementVersion()) {
            throw ApiException.conflict("ACKNOWLEDGEMENT_OUTDATED", "Please reload: the acknowledgement text has changed");
        }
        if (typedPhrase == null || !ACK_PHRASE.equals(typedPhrase.trim())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "PHRASE_MISMATCH",
                    "Type the phrase exactly as shown: " + ACK_PHRASE);
        }
        acknowledgements.save(new RiskAcknowledgement(userId, policy.getVersion(), textVersion,
                ackText.sha256(textVersion), typedPhrase.trim(), clock.instant(), clientIp, userAgent));
        return view(reconcile(userId), policy);
    }

    /** Voluntary pause from live trading; can be extended but never shortened. */
    @Transactional
    public StatusView selfExclude(long userId, int days) {
        if (days < 1 || days > 365) {
            throw ApiException.badRequest("INVALID_PERIOD", "Choose between 1 and 365 days");
        }
        ReadinessStatus status = lockOrCreate(userId);
        Instant until = clock.instant().plus(Duration.ofDays(days));
        if (status.getSelfExcludedUntil() != null && status.getSelfExcludedUntil().isAfter(until)) {
            throw ApiException.conflict("EXCLUSION_CANNOT_BE_SHORTENED",
                    "You are already excluded until " + status.getSelfExcludedUntil());
        }
        status.excludeUntil(until);
        audit(status, status.getState(), status.getState(), "SELF_EXCLUDED", ReadinessEvent.ActorType.USER, userId,
                "until " + until);
        return view(status, policies.active());
    }

    @Transactional(readOnly = true)
    public List<ReadinessEvent> history(long userId) {
        return events.findByUserIdOrderBySeqAsc(userId);
    }

    // ------------------------------------------------------------------ reviewer and admin

    public record ReviewCase(long userId, ReadinessState state, int failedAttempts, List<Integer> scores,
                             Instant cycleStartedAt, List<String> weakModules) {
    }

    @Transactional(readOnly = true)
    public List<ReviewCase> reviewQueue() {
        ReadinessPolicy policy = policies.active();
        return statuses.findByState(ReadinessState.REVIEW_REQUIRED).stream().map(s -> reviewCase(s, policy)).toList();
    }

    @Transactional(readOnly = true)
    public ReviewCase reviewCase(long userId) {
        ReadinessStatus s = statuses.findById(userId).orElseThrow(() -> ApiException.notFound("Readiness of user " + userId));
        return reviewCase(s, policies.active());
    }

    /**
     * An authorised reviewer (READINESS_REVIEWER or ADMIN, never the user themselves) decides a
     * REVIEW_REQUIRED case: allow a new set of attempts, or require specific modules to be redone.
     */
    @Transactional
    public StatusView decideReview(long reviewerId, String reviewerRole, long userId, ReadinessReview.Decision decision,
                                   String notes, List<String> remediationModules) {
        if (reviewerId == userId) {
            throw new ApiException(HttpStatus.FORBIDDEN, "SELF_REVIEW_FORBIDDEN", "You cannot review your own case");
        }
        ReadinessStatus status = lockOrCreate(userId);
        if (status.getState() != ReadinessState.REVIEW_REQUIRED) {
            throw ApiException.conflict("NOT_UNDER_REVIEW", "This user's readiness is not awaiting review");
        }
        List<String> modules = remediationModules == null ? List.of() : remediationModules.stream().distinct().toList();
        modules.forEach(catalog::module); // unknown slugs → 404
        if (decision == ReadinessReview.Decision.REQUIRE_REMEDIATION && modules.isEmpty()) {
            throw ApiException.badRequest("REMEDIATION_MODULES_REQUIRED", "Name at least one module to redo");
        }
        int failed = finalView(status, policies.active()).failedAttempts();
        reviews.save(new ReadinessReview(userId, reviewerId, reviewerRole, decision, notes,
                modules.isEmpty() ? null : String.join(",", modules), failed, clock.instant()));
        String detail = decision + "; reviewer " + reviewerId + " (" + reviewerRole + "); notes: " + notes;
        if (decision == ReadinessReview.Decision.ALLOW_RETAKE) {
            transition(status, ReadinessState.ASSESSMENT_PENDING, "REVIEW_ALLOWED_RETAKE", ReadinessEvent.ActorType.REVIEWER,
                    reviewerId, detail);
            status.startCycle(clock.instant());
        } else {
            status.requireRemediation(modules, clock.instant());
            transition(status, ReadinessState.IN_PROGRESS, "REVIEW_REQUIRED_REMEDIATION", ReadinessEvent.ActorType.REVIEWER,
                    reviewerId, detail + "; modules: " + modules);
        }
        return view(reconcile(userId), policies.active());
    }

    @Transactional
    public StatusView revoke(long adminId, long userId, String reason) {
        ReadinessStatus status = lockOrCreate(userId);
        if (status.getState() == ReadinessState.ACCESS_REVOKED && "ADMIN_REVOKED".equals(status.getStateReason())) {
            throw ApiException.conflict("ALREADY_REVOKED", "Access is already revoked by an administrator");
        }
        status.clearQualification();
        transition(status, ReadinessState.ACCESS_REVOKED, "ADMIN_REVOKED", ReadinessEvent.ActorType.ADMIN, adminId, reason);
        return view(status, policies.active());
    }

    /** Reinstatement restarts qualification: the user must pass the final assessment and acknowledge again. */
    @Transactional
    public StatusView reinstate(long adminId, long userId, String reason) {
        ReadinessStatus status = lockOrCreate(userId);
        if (status.getState() != ReadinessState.ACCESS_REVOKED) {
            throw ApiException.conflict("NOT_REVOKED", "Access is not revoked");
        }
        transition(status, ReadinessState.IN_PROGRESS, "ADMIN_REINSTATED", ReadinessEvent.ActorType.ADMIN, adminId, reason);
        return view(reconcile(userId), policies.active());
    }

    public record ChainVerification(long userId, int events, boolean intact, Integer firstBrokenSeq) {
    }

    @Transactional(readOnly = true)
    public ChainVerification verifyChain(long userId) {
        List<ReadinessEvent> chain = events.findByUserIdOrderBySeqAsc(userId);
        String prev = GENESIS;
        int expectedSeq = 1;
        for (ReadinessEvent e : chain) {
            if (e.getSeq() != expectedSeq || !e.getPrevHash().equals(prev) || !e.getHash().equals(e.computeHash())) {
                return new ChainVerification(userId, chain.size(), false, e.getSeq());
            }
            prev = e.getHash();
            expectedSeq++;
        }
        return new ChainVerification(userId, chain.size(), true, null);
    }

    // ------------------------------------------------------------------ eligibility (live trading)

    @Transactional
    public Eligibility eligibility(long userId) {
        ReadinessStatus s = reconcile(userId);
        Instant now = clock.instant();
        List<String> reasons = new ArrayList<>();
        if (s.getState() != ReadinessState.QUALIFIED) {
            reasons.add("NOT_QUALIFIED:" + s.getState());
        }
        if (s.getExpiresAt() != null && !now.isBefore(s.getExpiresAt())) {
            reasons.add("QUALIFICATION_EXPIRED");
        }
        if (s.getSelfExcludedUntil() != null && now.isBefore(s.getSelfExcludedUntil())) {
            reasons.add("SELF_EXCLUDED");
        }
        return new Eligibility(userId, s.getState(), reasons.isEmpty(), List.copyOf(reasons),
                s.getQualifiedPolicyVersion(), s.getQualifiedAt(), s.getExpiresAt(), s.getSelfExcludedUntil(), now);
    }

    // ------------------------------------------------------------------ the engine

    /** Applies every SYSTEM transition the evidence supports, one audited step at a time. */
    @Transactional
    public ReadinessStatus reconcile(long userId) {
        ReadinessStatus s = lockOrCreate(userId);
        ReadinessPolicy policy = policies.active();
        for (int i = 0; i < 8; i++) {
            Optional<Step> step = nextStep(s, policy);
            if (step.isEmpty()) {
                break;
            }
            Step st = step.get();
            if (st.to() == ReadinessState.QUALIFIED) {
                Instant now = clock.instant();
                s.qualify(now, policy.getVersion(), now.plus(policy.qualificationValidity()));
            }
            if (st.to() == ReadinessState.ACCESS_REVOKED) {
                s.clearQualification();
            }
            transition(s, st.to(), st.reason(), ReadinessEvent.ActorType.SYSTEM, null, st.detail());
            if (st.to() == ReadinessState.ASSESSMENT_PENDING) {
                s.startCycle(clock.instant());
            }
            if (st.to() == ReadinessState.ASSESSMENT_PENDING && !s.remediationModuleList().isEmpty()) {
                s.requireRemediation(List.of(), null);
            }
        }
        return s;
    }

    record Step(ReadinessState to, String reason, String detail) {
    }

    private Optional<Step> nextStep(ReadinessStatus s, ReadinessPolicy policy) {
        long userId = s.getUserId();
        Instant now = clock.instant();
        boolean requirementsMet = modulesDone(s) && exercisesDone(userId, policy);
        return switch (s.getState()) {
            case NOT_STARTED -> academy.anyProgress(userId) || !exercises.findByIdUserId(userId).isEmpty()
                    ? Optional.of(new Step(ReadinessState.IN_PROGRESS, "ACADEMY_STARTED", null)) : Optional.empty();
            case IN_PROGRESS -> requirementsMet
                    ? Optional.of(new Step(ReadinessState.ASSESSMENT_PENDING, "REQUIREMENTS_MET",
                    "all mandatory modules and practical exercises complete")) : Optional.empty();
            case ASSESSMENT_PENDING -> {
                if (!requirementsMet) {
                    yield Optional.of(new Step(ReadinessState.IN_PROGRESS, "REQUIREMENTS_CHANGED",
                            "policy " + policy.getVersion() + " requires more"));
                }
                FinalAssessmentView fin = finalView(s, policy);
                if (fin.passed()) {
                    yield Optional.of(new Step(ReadinessState.ACKNOWLEDGEMENT_PENDING, "FINAL_ASSESSMENT_PASSED",
                            "score " + fin.lastScorePct() + "% (pass mark " + policy.getFinalPassMarkPct() + "%)"));
                }
                if (fin.failedAttempts() >= policy.getMaxFailedFinalAttempts()) {
                    yield Optional.of(new Step(ReadinessState.REVIEW_REQUIRED, "MAX_FAILED_ATTEMPTS",
                            fin.failedAttempts() + " failed attempts"));
                }
                yield Optional.empty();
            }
            case ACKNOWLEDGEMENT_PENDING -> {
                Instant passedAt = passedFinalAt(s).orElse(null);
                Optional<RiskAcknowledgement> ack = acknowledgements.findFirstByUserIdOrderByAcceptedAtDesc(userId)
                        .filter(a -> passedAt != null && !a.getAcceptedAt().isBefore(passedAt)
                                && a.getTextVersion() == policy.getAcknowledgementVersion());
                yield ack.map(a -> new Step(ReadinessState.QUALIFIED, "RISK_ACKNOWLEDGED",
                        "acknowledgement v" + a.getTextVersion() + " " + a.getTextSha256()));
            }
            case QUALIFIED -> {
                if (s.getExpiresAt() != null && !now.isBefore(s.getExpiresAt())) {
                    yield Optional.of(new Step(ReadinessState.ACCESS_REVOKED, "QUALIFICATION_EXPIRED",
                            "qualified " + s.getQualifiedAt()));
                }
                yield requalificationPolicy(s).map(p -> new Step(ReadinessState.ACCESS_REVOKED, "POLICY_CHANGED",
                        "policy v" + p.getVersion() + " requires requalification"));
            }
            case ACCESS_REVOKED -> {
                // System revocations lead straight back to the assessment; admin revocations wait for a human.
                boolean systemRevoked = Set.of("QUALIFICATION_EXPIRED", "POLICY_CHANGED").contains(s.getStateReason());
                yield systemRevoked && requirementsMet
                        ? Optional.of(new Step(ReadinessState.ASSESSMENT_PENDING, "REQUALIFICATION_STARTED", null))
                        : Optional.empty();
            }
            case REVIEW_REQUIRED -> Optional.empty();
        };
    }

    private boolean modulesDone(ReadinessStatus s) {
        if (!academy.allMandatoryModulesCompleted(s.getUserId())) {
            return false;
        }
        // After a reviewer requires remediation, those module quizzes must be passed again after the review.
        for (String module : s.remediationModuleList()) {
            boolean redone = attempts.findByUserIdAndQuizKeyOrderByStartedAtDesc(s.getUserId(), module).stream()
                    .anyMatch(a -> Boolean.TRUE.equals(a.getPassed()) && a.getSubmittedAt().isAfter(s.getRemediationSince()));
            if (!redone) {
                return false;
            }
        }
        return true;
    }

    private boolean exercisesDone(long userId, ReadinessPolicy policy) {
        Map<String, ExerciseProgress> progress = exercises.findByIdUserId(userId).stream()
                .collect(Collectors.toMap(e -> e.getId().getSlug(), Function.identity()));
        return policy.requiredExerciseSlugs().stream()
                .allMatch(slug -> progress.containsKey(slug) && progress.get(slug).getCompletedAt() != null);
    }

    private List<QuizAttempt> finalAttemptsInCycle(ReadinessStatus s) {
        Instant since = s.getCycleStartedAt() == null ? Instant.EPOCH : s.getCycleStartedAt();
        return attempts.findByUserIdAndQuizKeyOrderByStartedAtDesc(s.getUserId(), QuizAttempt.FINAL).stream()
                .filter(a -> !a.getStartedAt().isBefore(since)).toList();
    }

    private Optional<Instant> passedFinalAt(ReadinessStatus s) {
        return finalAttemptsInCycle(s).stream().filter(a -> Boolean.TRUE.equals(a.getPassed()))
                .map(QuizAttempt::getSubmittedAt).max(Comparator.naturalOrder());
    }

    private FinalAssessmentView finalView(ReadinessStatus s, ReadinessPolicy policy) {
        Instant now = clock.instant();
        List<QuizAttempt> cycle = finalAttemptsInCycle(s);
        // A submitted fail, or an attempt abandoned past its time limit, counts as a failed attempt.
        List<QuizAttempt> failed = cycle.stream().filter(a -> a.getSubmittedAt() != null
                ? !Boolean.TRUE.equals(a.getPassed()) : !now.isBefore(a.getExpiresAt())).toList();
        Instant lastFailure = failed.stream().map(a -> a.getSubmittedAt() != null ? a.getSubmittedAt() : a.getExpiresAt())
                .max(Comparator.naturalOrder()).orElse(null);
        Integer lastScore = cycle.stream().filter(a -> a.getSubmittedAt() != null)
                .max(Comparator.comparing(QuizAttempt::getSubmittedAt)).map(QuizAttempt::getScorePct).orElse(null);
        boolean passed = cycle.stream().anyMatch(a -> Boolean.TRUE.equals(a.getPassed()));
        Catalog.QuizSpec spec = catalog.content().finalAssessment();
        return new FinalAssessmentView(s.getState() == ReadinessState.ASSESSMENT_PENDING, failed.size(),
                policy.getMaxFailedFinalAttempts(), lastFailure == null ? null : lastFailure.plus(policy.finalRetakeCooldown()),
                lastScore, passed, policy.getFinalPassMarkPct(), spec.questionsServed(), spec.timeLimitMinutes());
    }

    private Optional<ReadinessPolicy> requalificationPolicy(ReadinessStatus s) {
        if (s.getQualifiedPolicyVersion() == null) {
            return Optional.empty();
        }
        return policyRepository.findAll().stream()
                .filter(p -> p.getVersion() > s.getQualifiedPolicyVersion() && p.isRequiresRequalification()
                        && !p.getEffectiveFrom().isAfter(clock.instant()))
                .findFirst();
    }

    private ReviewCase reviewCase(ReadinessStatus s, ReadinessPolicy policy) {
        List<QuizAttempt> cycle = finalAttemptsInCycle(s);
        List<Integer> scores = cycle.stream().filter(a -> a.getScorePct() != null).map(QuizAttempt::getScorePct).toList();
        // Modules whose questions were missed in the failed final attempts: a hint for remediation.
        Map<String, String> moduleOfQuestion = catalog.content().modules().stream()
                .flatMap(m -> m.questions().stream().map(q -> Map.entry(q.key(), m.slug())))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        List<String> weak = cycle.stream().flatMap(a -> a.getQuestions().stream())
                .filter(q -> Boolean.FALSE.equals(q.getCorrect()))
                .map(q -> moduleOfQuestion.get(q.getQuestionKey())).filter(java.util.Objects::nonNull)
                .distinct().toList();
        return new ReviewCase(s.getUserId(), s.getState(), finalView(s, policy).failedAttempts(), scores,
                s.getCycleStartedAt(), weak);
    }

    // ------------------------------------------------------------------ transitions and audit

    private ReadinessStatus lockOrCreate(long userId) {
        return statuses.lock(userId).orElseGet(() -> {
            ReadinessStatus created = statuses.saveAndFlush(new ReadinessStatus(userId, policies.active().getVersion(),
                    clock.instant()));
            audit(created, null, ReadinessState.NOT_STARTED, "CREATED", ReadinessEvent.ActorType.SYSTEM, null, null);
            return created;
        });
    }

    void transition(ReadinessStatus s, ReadinessState to, String reason, ReadinessEvent.ActorType actor, Long actorId,
                    String detail) {
        ReadinessState from = s.getState();
        if (!from.canMoveTo(to)) {
            throw ApiException.conflict("INVALID_READINESS_TRANSITION", "Readiness cannot move from " + from + " to " + to);
        }
        int policyVersion = policies.active().getVersion();
        s.moveTo(to, reason, policyVersion, clock.instant());
        audit(s, from, to, reason, actor, actorId, detail);
        outbox.append(Topics.READINESS_CHANGED, String.valueOf(s.getUserId()), new Events.ReadinessChanged(
                s.getUserId(), from.name(), to.name(), reason, to == ReadinessState.QUALIFIED, policyVersion, clock.instant()));
    }

    private void audit(ReadinessStatus s, ReadinessState from, ReadinessState to, String reason,
                       ReadinessEvent.ActorType actor, Long actorId, String detail) {
        Optional<ReadinessEvent> last = events.findFirstByUserIdOrderBySeqDesc(s.getUserId());
        events.save(new ReadinessEvent(s.getUserId(), last.map(e -> e.getSeq() + 1).orElse(1),
                from == null ? null : from.name(), to.name(), reason, actor, actorId, s.getPolicyVersion(), detail,
                clock.instant(), last.map(ReadinessEvent::getHash).orElse(GENESIS)));
    }

    private StatusView view(ReadinessStatus s, ReadinessPolicy policy) {
        long userId = s.getUserId();
        AcademyService.Overview overview = academy.overview(userId);
        Map<String, ExerciseProgress> progress = exercises.findByIdUserId(userId).stream()
                .collect(Collectors.toMap(e -> e.getId().getSlug(), Function.identity()));
        Set<String> required = Set.copyOf(policy.requiredExerciseSlugs());
        List<ExerciseView> ex = catalog.content().exercises().stream().map(e -> {
            ExerciseProgress p = progress.get(e.slug());
            return new ExerciseView(e.slug(), e.title(), e.module(), e.description(), required.contains(e.slug()),
                    p == null ? null : p.getStartedAt(), p == null ? null : p.getCompletedAt());
        }).toList();
        boolean acknowledged = s.getState() == ReadinessState.QUALIFIED;
        Checklist checklist = new Checklist(overview.modulesCompleted(), overview.moduleCount(), modulesDone(s),
                s.remediationModuleList(), ex, exercisesDone(userId, policy), finalView(s, policy), acknowledged);
        Eligibility eligibility = eligibilityOf(s);
        return new StatusView(s.getState(), s.getStateReason(), s.getPolicyVersion(), s.getQualifiedAt(), s.getExpiresAt(),
                s.getSelfExcludedUntil(), eligibility.eligible(), nextStep(s, checklist), checklist, PolicySummary.of(policy));
    }

    private Eligibility eligibilityOf(ReadinessStatus s) {
        Instant now = clock.instant();
        boolean eligible = s.getState() == ReadinessState.QUALIFIED
                && (s.getExpiresAt() == null || now.isBefore(s.getExpiresAt()))
                && (s.getSelfExcludedUntil() == null || !now.isBefore(s.getSelfExcludedUntil()));
        return new Eligibility(s.getUserId(), s.getState(), eligible, List.of(), s.getQualifiedPolicyVersion(),
                s.getQualifiedAt(), s.getExpiresAt(), s.getSelfExcludedUntil(), now);
    }

    private static String nextStep(ReadinessStatus s, Checklist c) {
        return switch (s.getState()) {
            case NOT_STARTED -> "Start the Trading Academy.";
            case IN_PROGRESS -> !c.remediationModules().isEmpty()
                    ? "A reviewer asked you to redo: " + String.join(", ", c.remediationModules()) + "."
                    : !c.modulesDone() ? "Complete every Academy module (lessons and quiz)."
                    : "Complete the practical exercises in the demo.";
            case ASSESSMENT_PENDING -> "Take the final readiness assessment.";
            case REVIEW_REQUIRED -> "An authorised reviewer will look at your attempts before you can continue.";
            case ACKNOWLEDGEMENT_PENDING -> "Read and sign the risk acknowledgement.";
            case QUALIFIED -> "You meet TradeFlow's readiness requirements. Live trading also needs a broker connection.";
            case ACCESS_REVOKED -> switch (s.getStateReason()) {
                case "QUALIFICATION_EXPIRED", "POLICY_CHANGED" -> "Requalify: complete any new requirements and retake the assessment.";
                default -> "Live-trading access was revoked by an administrator. Contact support.";
            };
        };
    }
}
