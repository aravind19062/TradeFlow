package com.luffy.tradeflow_api_gateway;

import com.luffy.tradeflow.common.testsupport.TestJwts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Edge rules, checked without any downstream service running. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GatewaySecurityTest {

    @Autowired MockMvc mvc;

    @Test
    void internalEndpointsAreNeverReachableFromOutside() throws Exception {
        mvc.perform(get("/internal/orders/status")).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/orders/status").header("Authorization", TestJwts.bearer(TestJwts.service())))
                .andExpect(status().isForbidden());
        mvc.perform(get("/internal/orders/status").header("Authorization", TestJwts.bearer(TestJwts.admin(1))))
                .andExpect(status().isForbidden());
    }

    @Test
    void protectedAndAdminRoutesAreCheckedAtTheEdge() throws Exception {
        mvc.perform(get("/api/orders")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(get("/api/orders").header("Authorization", "Bearer not.a.jwt")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/orders/sagas").header("Authorization", TestJwts.bearer(TestJwts.user(5))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(get("/not-routed")).andExpect(status().isUnauthorized());
    }

    @Test
    void circuitBreakerFallbackIsAnExplicit503() throws Exception {
        mvc.perform(get("/fallback/order-service"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"))
                .andExpect(jsonPath("$.service").value("order-service"));
        mvc.perform(post("/fallback/user-service")).andExpect(status().isServiceUnavailable());
    }

    @Test
    void routeWithNoAvailableInstanceIsAnExplicit503() throws Exception {
        // No Eureka and no instances in the test profile: the load balancer finds nothing, and the
        // route's circuit breaker forwards to the explicit 503 fallback (MockMvc records the forward).
        mvc.perform(get("/api/orders").header("Authorization", TestJwts.bearer(TestJwts.user(5))))
                .andExpect(forwardedUrl("/fallback/order-service"));
    }

    @Test
    void refusesThePlaceholderSecretOutsideLocalProfile() {
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");
        assertThatThrownBy(() -> new GatewaySecurityConfig().jwtDecoder(
                "local-dev-only-insecure-jwt-secret-do-not-use-in-prod", "tradeflow-user-service", prod))
                .hasMessageContaining("outside the 'local' profile");
        assertThatThrownBy(() -> new GatewaySecurityConfig().jwtDecoder("", "x", prod))
                .hasMessageContaining("TRADEFLOW_JWT_SECRET");
    }
}
