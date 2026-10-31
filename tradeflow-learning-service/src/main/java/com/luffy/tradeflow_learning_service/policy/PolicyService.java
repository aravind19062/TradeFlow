package com.luffy.tradeflow_learning_service.policy;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

@Service
public class PolicyService {

    private final ReadinessPolicyRepository repository;
    private final Clock clock;

    public PolicyService(ReadinessPolicyRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /** The policy in force now: the highest version whose effective date has passed. */
    @Transactional(readOnly = true)
    public ReadinessPolicy active() {
        return repository.findFirstByEffectiveFromLessThanEqualOrderByVersionDesc(clock.instant())
                .orElseThrow(() -> new IllegalStateException("No readiness policy is in force; check migrations"));
    }

    @Transactional(readOnly = true)
    public ReadinessPolicy version(int version) {
        return repository.findById(version)
                .orElseThrow(() -> com.luffy.tradeflow.common.web.ApiException.notFound("Policy version " + version));
    }
}
