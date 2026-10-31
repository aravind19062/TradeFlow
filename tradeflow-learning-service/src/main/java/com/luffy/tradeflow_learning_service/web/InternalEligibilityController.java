package com.luffy.tradeflow_learning_service.web;

import com.luffy.tradeflow_learning_service.readiness.ReadinessService;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Live-trading eligibility for other services (SERVICE token only; the gateway never routes
 * /internal/**). Evaluated fresh on every call; callers must treat any failure as "not eligible".
 */
@Hidden
@RestController
public class InternalEligibilityController {

    private final ReadinessService readiness;

    public InternalEligibilityController(ReadinessService readiness) {
        this.readiness = readiness;
    }

    @GetMapping("/internal/readiness/{userId}/eligibility")
    public ReadinessService.Eligibility eligibility(@PathVariable long userId) {
        return readiness.eligibility(userId);
    }
}
