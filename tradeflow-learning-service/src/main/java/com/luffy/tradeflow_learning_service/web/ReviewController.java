package com.luffy.tradeflow_learning_service.web;

import com.luffy.tradeflow.common.security.AuthenticatedUser;
import com.luffy.tradeflow_learning_service.readiness.ReadinessReview;
import com.luffy.tradeflow_learning_service.readiness.ReadinessService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Authorised reviewers (READINESS_REVIEWER or ADMIN) decide REVIEW_REQUIRED cases. Audited. */
@RestController
@RequestMapping("/api/admin/readiness/reviews")
@Tag(name = "Readiness review")
public class ReviewController {

    public record DecisionRequest(@NotNull ReadinessReview.Decision decision,
                                  @NotBlank @Size(min = 20, max = 1000) String notes,
                                  @Size(max = 10) List<String> remediationModules) {
    }

    private final ReadinessService readiness;

    public ReviewController(ReadinessService readiness) {
        this.readiness = readiness;
    }

    @GetMapping
    @Operation(summary = "Cases awaiting review")
    public List<ReadinessService.ReviewCase> queue() {
        return readiness.reviewQueue();
    }

    @GetMapping("/{userId}")
    @Operation(summary = "One case: failed attempts, scores and modules where questions were missed")
    public ReadinessService.ReviewCase reviewCase(@PathVariable long userId) {
        return readiness.reviewCase(userId);
    }

    @PostMapping("/{userId}/decision")
    @Operation(summary = "Allow a retake, or require specific modules to be redone (never your own case)")
    public ReadinessService.StatusView decide(AuthenticatedUser reviewer, @PathVariable long userId,
                                              @Valid @RequestBody DecisionRequest request) {
        String role = reviewer.roles().contains("ADMIN") ? "ADMIN" : "READINESS_REVIEWER";
        return readiness.decideReview(reviewer.id(), role, userId, request.decision(), request.notes(),
                request.remediationModules());
    }
}
