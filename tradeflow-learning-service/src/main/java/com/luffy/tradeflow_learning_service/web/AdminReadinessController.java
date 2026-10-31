package com.luffy.tradeflow_learning_service.web;

import com.luffy.tradeflow.common.security.AuthenticatedUser;
import com.luffy.tradeflow_learning_service.policy.PolicyAdminService;
import com.luffy.tradeflow_learning_service.policy.ReadinessPolicy;
import com.luffy.tradeflow_learning_service.readiness.ReadinessService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** ADMIN: a user's readiness with its verified audit chain; revoke / reinstate; policy versions. */
@RestController
@RequestMapping("/api/admin/readiness")
@Tag(name = "Admin: readiness")
public class AdminReadinessController {

    public record ReasonRequest(@NotBlank @Size(min = 10, max = 500) String reason) {
    }

    public record UserReadiness(ReadinessService.StatusView status, List<ReadinessController.HistoryItem> history,
                                ReadinessService.ChainVerification chain) {
    }

    private final ReadinessService readiness;
    private final PolicyAdminService policies;

    public AdminReadinessController(ReadinessService readiness, PolicyAdminService policies) {
        this.readiness = readiness;
        this.policies = policies;
    }

    @GetMapping("/users/{userId}")
    @Operation(summary = "ADMIN: readiness, audit trail and hash-chain verification")
    public UserReadiness user(@PathVariable long userId) {
        return new UserReadiness(readiness.status(userId),
                readiness.history(userId).stream().map(ReadinessController.HistoryItem::of).toList(),
                readiness.verifyChain(userId));
    }

    @PostMapping("/users/{userId}/revoke")
    @Operation(summary = "ADMIN: revoke live-trading readiness (reason required)")
    public ReadinessService.StatusView revoke(AuthenticatedUser admin, @PathVariable long userId,
                                              @Valid @RequestBody ReasonRequest request) {
        return readiness.revoke(admin.id(), userId, request.reason());
    }

    @PostMapping("/users/{userId}/reinstate")
    @Operation(summary = "ADMIN: reinstate after revocation (user must requalify)")
    public ReadinessService.StatusView reinstate(AuthenticatedUser admin, @PathVariable long userId,
                                                 @Valid @RequestBody ReasonRequest request) {
        return readiness.reinstate(admin.id(), userId, request.reason());
    }

    @GetMapping("/policies")
    @Operation(summary = "ADMIN: all policy versions")
    public List<ReadinessPolicy> policies() {
        return policies.all();
    }

    @PostMapping("/policies")
    @Operation(summary = "ADMIN: publish a new immutable policy version")
    public ReadinessPolicy publish(AuthenticatedUser admin, @Valid @RequestBody PolicyAdminService.NewPolicy request) {
        return policies.publish(admin.id(), request);
    }
}
