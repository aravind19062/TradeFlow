package com.luffy.tradeflow_learning_service.policy;

import com.luffy.tradeflow.common.web.ApiException;
import com.luffy.tradeflow_learning_service.academy.Catalog;
import com.luffy.tradeflow_learning_service.academy.ContentCatalog;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Publishes a new, immutable readiness-policy version (ADMIN). Existing versions are never edited. */
@Service
public class PolicyAdminService {

    public record NewPolicy(int modulePassMarkPct, int finalPassMarkPct, int finalRetakeCooldownHours,
                            int maxFailedFinalAttempts, int qualificationValidityDays, List<String> requiredExercises,
                            int acknowledgementVersion, boolean requiresRequalification, Instant effectiveFrom,
                            String notes) {
    }

    private final ReadinessPolicyRepository repository;
    private final ContentCatalog catalog;
    private final Clock clock;

    public PolicyAdminService(ReadinessPolicyRepository repository, ContentCatalog catalog, Clock clock) {
        this.repository = repository;
        this.catalog = catalog;
        this.clock = clock;
    }

    @Transactional
    public ReadinessPolicy publish(long adminId, NewPolicy p) {
        Set<String> known = catalog.content().exercises().stream().map(Catalog.Exercise::slug).collect(Collectors.toSet());
        List<String> unknown = p.requiredExercises().stream().filter(e -> !known.contains(e)).toList();
        if (!unknown.isEmpty()) {
            throw ApiException.badRequest("UNKNOWN_EXERCISE", "Unknown exercises: " + unknown);
        }
        if (p.modulePassMarkPct() < 50 || p.modulePassMarkPct() > 100 || p.finalPassMarkPct() < 50
                || p.finalPassMarkPct() > 100 || p.finalRetakeCooldownHours() < 0 || p.maxFailedFinalAttempts() < 1
                || p.qualificationValidityDays() < 1) {
            throw ApiException.badRequest("INVALID_POLICY", "Pass marks 50–100, cooldown ≥ 0, attempts ≥ 1, validity ≥ 1 day");
        }
        Instant now = clock.instant();
        Instant from = p.effectiveFrom() == null || p.effectiveFrom().isBefore(now) ? now : p.effectiveFrom();
        int version = repository.findFirstByOrderByVersionDesc().map(ReadinessPolicy::getVersion).orElse(0) + 1;
        return repository.save(new ReadinessPolicy(version, p.modulePassMarkPct(), p.finalPassMarkPct(),
                p.finalRetakeCooldownHours(), p.maxFailedFinalAttempts(), p.qualificationValidityDays(),
                p.requiredExercises(), p.acknowledgementVersion(), p.requiresRequalification(), from, adminId, now,
                p.notes()));
    }

    @Transactional(readOnly = true)
    public List<ReadinessPolicy> all() {
        return repository.findAll(org.springframework.data.domain.Sort.by("version"));
    }
}
