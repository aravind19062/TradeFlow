package com.luffy.tradeflow_api_gateway;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Target of the gateway's circuit breakers: when a service is down, timing out or its
 * breaker is open, the client gets a fast, explicit 503 instead of a hanging request.
 */
@RestController
public class FallbackController {

    @RequestMapping("/fallback/{service}")
    public org.springframework.http.ResponseEntity<ProblemDetail> unavailable(@PathVariable String service) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                service + " is unavailable; retry shortly");
        problem.setProperty("code", "DEPENDENCY_UNAVAILABLE");
        problem.setProperty("service", service);
        return org.springframework.http.ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", "5").body(problem);
    }
}
