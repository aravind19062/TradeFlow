package com.luffy.tradeflow_learning_service.academy;

import com.luffy.tradeflow.common.web.ApiException;
import com.luffy.tradeflow_learning_service.policy.ReadinessPolicy;
import com.luffy.tradeflow_learning_service.progress.LessonProgress;
import com.luffy.tradeflow_learning_service.progress.LessonProgressRepository;
import com.luffy.tradeflow_learning_service.progress.QuizAttempt;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Lessons, per-user progress and module quizzes of the Trading Academy. */
@Service
public class AcademyService {

    public enum ModuleStatus { NOT_STARTED, IN_PROGRESS, QUIZ_AVAILABLE, COMPLETED }

    /** A lesson was completed (readiness re-evaluates on this). */
    public record LessonCompleted(long userId, String moduleSlug, String lessonSlug) {
    }

    public record LessonSummary(String slug, String title, boolean completed) {
    }

    public record ModuleSummary(String slug, String title, String summary, int position, boolean mandatory,
                                int lessonsCompleted, int lessonCount, Integer bestScorePct, boolean quizPassed,
                                ModuleStatus status, List<LessonSummary> lessons, int quizQuestions,
                                int quizTimeLimitMinutes) {
    }

    public record LessonView(String moduleSlug, String moduleTitle, String slug, String title, String body,
                             boolean completed, String previousLesson, String nextLesson) {
    }

    public record Overview(int contentVersion, int policyVersion, int modulePassMarkPct, int modulesCompleted,
                           int moduleCount, List<ModuleSummary> modules) {
    }

    private final ContentCatalog catalog;
    private final LessonProgressRepository progress;
    private final QuizService quizzes;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public AcademyService(ContentCatalog catalog, LessonProgressRepository progress, QuizService quizzes,
                          ApplicationEventPublisher events, Clock clock) {
        this.catalog = catalog;
        this.progress = progress;
        this.quizzes = quizzes;
        this.events = events;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Overview overview(long userId) {
        Set<String> done = completedLessons(userId);
        ReadinessPolicy policy = quizzes.policy();
        List<ModuleSummary> modules = catalog.content().modules().stream().map(m -> summary(userId, m, done)).toList();
        int completed = (int) modules.stream().filter(m -> m.status() == ModuleStatus.COMPLETED).count();
        return new Overview(catalog.content().version(), policy.getVersion(), policy.getModulePassMarkPct(), completed,
                modules.size(), modules);
    }

    @Transactional(readOnly = true)
    public ModuleSummary module(long userId, String slug) {
        return summary(userId, catalog.module(slug), completedLessons(userId));
    }

    @Transactional(readOnly = true)
    public LessonView lesson(long userId, String moduleSlug, String lessonSlug) {
        Catalog.Module module = catalog.module(moduleSlug);
        List<Catalog.Lesson> lessons = module.lessons();
        for (int i = 0; i < lessons.size(); i++) {
            Catalog.Lesson l = lessons.get(i);
            if (l.slug().equals(lessonSlug)) {
                boolean completed = progress.existsById(new LessonProgress.Key(userId, moduleSlug, lessonSlug));
                return new LessonView(moduleSlug, module.title(), l.slug(), l.title(), l.body(), completed,
                        i > 0 ? lessons.get(i - 1).slug() : null, i + 1 < lessons.size() ? lessons.get(i + 1).slug() : null);
            }
        }
        throw ApiException.notFound("Lesson " + lessonSlug);
    }

    @Transactional
    public void completeLesson(long userId, String moduleSlug, String lessonSlug) {
        Catalog.Module module = catalog.module(moduleSlug);
        Catalog.Lesson lesson = module.lessons().stream().filter(l -> l.slug().equals(lessonSlug)).findFirst()
                .orElseThrow(() -> ApiException.notFound("Lesson " + lessonSlug));
        LessonProgress.Key key = new LessonProgress.Key(userId, moduleSlug, lessonSlug);
        if (!progress.existsById(key)) {
            progress.save(new LessonProgress(key, catalog.content().version(), lesson.hash(), clock.instant()));
            events.publishEvent(new LessonCompleted(userId, moduleSlug, lessonSlug));
        }
    }

    /** Module quizzes open once every lesson of the module has been read. */
    @Transactional
    public QuizService.AttemptView startModuleQuiz(long userId, String moduleSlug) {
        Catalog.Module module = catalog.module(moduleSlug);
        Set<String> done = completedLessons(userId);
        boolean allRead = module.lessons().stream().allMatch(l -> done.contains(moduleSlug + "/" + l.slug()));
        if (!allRead) {
            throw ApiException.conflict("LESSONS_INCOMPLETE", "Read every lesson of this module before taking its quiz");
        }
        ReadinessPolicy policy = quizzes.policy();
        return quizzes.start(userId, moduleSlug, policy.getModulePassMarkPct(), policy.getVersion());
    }

    /** True when every mandatory module's lessons are read and its quiz is passed. */
    @Transactional(readOnly = true)
    public boolean allMandatoryModulesCompleted(long userId) {
        Set<String> done = completedLessons(userId);
        return catalog.content().modules().stream().filter(Catalog.Module::mandatory)
                .allMatch(m -> summary(userId, m, done).status() == ModuleStatus.COMPLETED);
    }

    @Transactional(readOnly = true)
    public boolean anyProgress(long userId) {
        return !progress.findByIdUserId(userId).isEmpty();
    }

    private ModuleSummary summary(long userId, Catalog.Module m, Set<String> done) {
        List<LessonSummary> lessons = m.lessons().stream()
                .map(l -> new LessonSummary(l.slug(), l.title(), done.contains(m.slug() + "/" + l.slug()))).toList();
        int read = (int) lessons.stream().filter(LessonSummary::completed).count();
        Integer best = quizzes.best(userId, m.slug()).map(QuizAttempt::getScorePct).orElse(null);
        boolean passed = quizzes.passed(userId, m.slug());
        ModuleStatus status = passed && read == lessons.size() ? ModuleStatus.COMPLETED
                : read == lessons.size() ? ModuleStatus.QUIZ_AVAILABLE
                : read > 0 || best != null ? ModuleStatus.IN_PROGRESS : ModuleStatus.NOT_STARTED;
        return new ModuleSummary(m.slug(), m.title(), m.summary(), m.position(), m.mandatory(), read, lessons.size(),
                best, passed, status, lessons, m.quiz().questionsServed(), m.quiz().timeLimitMinutes());
    }

    private Set<String> completedLessons(long userId) {
        return progress.findByIdUserId(userId).stream()
                .map(p -> p.getId().getModuleSlug() + "/" + p.getId().getLessonSlug()).collect(Collectors.toSet());
    }
}
