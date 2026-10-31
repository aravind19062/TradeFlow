package com.luffy.tradeflow_learning_service.web;

import com.luffy.tradeflow.common.security.AuthenticatedUser;
import com.luffy.tradeflow_learning_service.academy.QuizService;
import com.luffy.tradeflow_learning_service.readiness.ExerciseService;
import com.luffy.tradeflow_learning_service.readiness.ReadinessEvent;
import com.luffy.tradeflow_learning_service.readiness.ReadinessService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/** The caller's own trading readiness. Passing is knowledge-based; it is not a suitability judgement. */
@RestController
@RequestMapping("/api/readiness")
@Tag(name = "Trading readiness")
public class ReadinessController {

    public record AcknowledgeRequest(int textVersion, @NotBlank @Size(max = 100) String typedPhrase) {
    }

    public record SelfExclusionRequest(@Min(1) @Max(365) int days) {
    }

    public record SizingAnswer(@Positive long quantity) {
    }

    public record HistoryItem(int seq, String fromState, String toState, String reasonCode, String actorType,
                              int policyVersion, Instant occurredAt) {
        static HistoryItem of(ReadinessEvent e) {
            return new HistoryItem(e.getSeq(), e.getFromState(), e.getToState(), e.getReasonCode(), e.getActorType(),
                    e.getPolicyVersion(), e.getOccurredAt());
        }
    }

    private final ReadinessService readiness;
    private final ExerciseService exercises;

    public ReadinessController(ReadinessService readiness, ExerciseService exercises) {
        this.readiness = readiness;
        this.exercises = exercises;
    }

    @GetMapping
    @Operation(summary = "Readiness state, checklist and next step")
    public ReadinessService.StatusView status(AuthenticatedUser user) {
        return readiness.status(user.id());
    }

    @PostMapping("/assessment/attempts")
    @Operation(summary = "Start (or resume) the final assessment; enforces cooldown and attempt limits")
    public QuizService.AttemptView startAssessment(AuthenticatedUser user) {
        return readiness.startFinalAssessment(user.id());
    }

    @GetMapping("/acknowledgement")
    @Operation(summary = "Current risk-acknowledgement text, version and the phrase to type")
    public ReadinessService.AcknowledgementView acknowledgementText() {
        return readiness.acknowledgementText();
    }

    @PostMapping("/acknowledgement")
    @Operation(summary = "Sign the risk acknowledgement (after passing the final assessment)")
    public ReadinessService.StatusView acknowledge(AuthenticatedUser user, @Valid @RequestBody AcknowledgeRequest request,
                                                   HttpServletRequest http) {
        return readiness.acknowledge(user.id(), request.textVersion(), request.typedPhrase(), http.getRemoteAddr(),
                http.getHeader("User-Agent"));
    }

    @PostMapping("/self-exclusion")
    @Operation(summary = "Exclude yourself from live trading for 1–365 days (cannot be shortened)")
    public ReadinessService.StatusView selfExclude(AuthenticatedUser user, @Valid @RequestBody SelfExclusionRequest request) {
        return readiness.selfExclude(user.id(), request.days());
    }

    @GetMapping("/history")
    @Operation(summary = "Your readiness audit trail")
    public List<HistoryItem> history(AuthenticatedUser user) {
        return readiness.history(user.id()).stream().map(HistoryItem::of).toList();
    }

    @PostMapping("/exercises/{slug}/start")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Start a practical exercise; demo actions count only after this")
    public void startExercise(AuthenticatedUser user, @PathVariable String slug) {
        exercises.start(user.id(), slug);
    }

    @PostMapping("/exercises/position-sizing/scenario")
    @Operation(summary = "The active position-sizing scenario (issues one if needed)")
    public ExerciseService.ScenarioView scenario(AuthenticatedUser user) {
        return exercises.scenario(user.id());
    }

    @PostMapping("/exercises/position-sizing/answer")
    @Operation(summary = "Check your computed quantity")
    public ExerciseService.AnswerResult answer(AuthenticatedUser user, @Valid @RequestBody SizingAnswer request) {
        return exercises.answer(user.id(), request.quantity());
    }
}
