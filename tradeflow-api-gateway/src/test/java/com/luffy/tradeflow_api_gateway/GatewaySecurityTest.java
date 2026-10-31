package com.luffy.tradeflow_api_gateway;

import com.luffy.tradeflow.common.testsupport.TestJwts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
    void onlyTokensSignedByUserServiceAreAccepted() throws Exception {
        // Signed with a key that is not in user-service's public key ring.
        mvc.perform(get("/api/orders").header("Authorization", TestJwts.bearer(TestJwts.untrustedUser(5))))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/orders").header("Authorization", TestJwts.bearer(TestJwts.expiredUser(5))))
                .andExpect(status().isUnauthorized());
        // The former shared-secret scheme no longer works at all.
        com.nimbusds.jwt.SignedJWT hs = new com.nimbusds.jwt.SignedJWT(
                new com.nimbusds.jose.JWSHeader(com.nimbusds.jose.JWSAlgorithm.HS256),
                new com.nimbusds.jwt.JWTClaimsSet.Builder().issuer(TestJwts.ISSUER).subject("1").audience("tradeflow")
                        .claim("roles", java.util.List.of("ADMIN"))
                        .expirationTime(new java.util.Date(System.currentTimeMillis() + 60_000)).build());
        hs.sign(new com.nimbusds.jose.crypto.MACSigner("local-dev-only-insecure-jwt-secret-do-not-use-in-prod".getBytes()));
        mvc.perform(get("/api/admin/orders/sagas").header("Authorization", "Bearer " + hs.serialize()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void sessionEndpointsArePublicButMfaManagementIsNot() throws Exception {
        // Reach the route (and its 503 fallback here, as no user-service runs in this test).
        mvc.perform(post("/api/auth/refresh")).andExpect(forwardedUrl("/fallback/user-service"));
        mvc.perform(post("/api/auth/login/mfa")).andExpect(forwardedUrl("/fallback/user-service"));
        mvc.perform(post("/api/auth/logout")).andExpect(forwardedUrl("/fallback/user-service"));
        mvc.perform(post("/api/auth/step-up")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/mfa/totp/enroll")).andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/auth/service-token")).andExpect(status().isUnauthorized());
    }

    @Test
    void liveMutationsNeedAStepUpTokenWhichIsUsableOnlyForLiveTrading() throws Exception {
        String access = TestJwts.bearer(TestJwts.user(5));
        String stepUp = TestJwts.bearer(TestJwts.withAudience(5, "tradeflow-live",
                java.util.Map.of("amr", java.util.List.of("pwd", "otp"))));
        // Reads and routing: ordinary token reaches the live service (its 503 fallback here).
        mvc.perform(get("/api/live/status").header("Authorization", access))
                .andExpect(forwardedUrl("/fallback/live-trading-service"));
        // Placing an order or linking a broker with an ordinary token is refused at the edge.
        mvc.perform(post("/api/live/orders").header("Authorization", access)).andExpect(status().isForbidden());
        mvc.perform(post("/api/live/orders/preview").header("Authorization", access)).andExpect(status().isForbidden());
        mvc.perform(post("/api/live/broker/connect").header("Authorization", access)).andExpect(status().isForbidden());
        mvc.perform(post("/api/live/orders").header("Authorization", stepUp))
                .andExpect(forwardedUrl("/fallback/live-trading-service"));
        mvc.perform(post("/api/live/orders")).andExpect(status().isUnauthorized());
        // A step-up token is not an access token anywhere else (not even for an admin).
        mvc.perform(get("/api/orders").header("Authorization", stepUp)).andExpect(status().isForbidden());
        String adminStepUp = TestJwts.bearer(TestJwts.withAudience(1, "tradeflow-live", java.util.Map.of()));
        mvc.perform(get("/api/admin/live/kill-switch").header("Authorization", adminStepUp)).andExpect(status().isForbidden());
        // Live admin is ADMIN only.
        mvc.perform(get("/api/admin/live/kill-switch").header("Authorization", access)).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/live/kill-switch").header("Authorization", TestJwts.bearer(TestJwts.admin(1))))
                .andExpect(forwardedUrl("/fallback/live-trading-service"));
    }

    @Test
    void changingALiveKillSwitchNeedsTheAdministratorsOwnStepUpToken() throws Exception {
        String admin = TestJwts.bearer(TestJwts.admin(1));
        String adminStepUp = TestJwts.bearer(TestJwts.withAudience(1, "ADMIN", "tradeflow-live",
                java.util.Map.of("amr", java.util.List.of("pwd", "otp"))));
        String userStepUp = TestJwts.bearer(TestJwts.withAudience(5, "tradeflow-live",
                java.util.Map.of("amr", java.util.List.of("pwd", "otp"))));
        for (String path : java.util.List.of("/api/admin/live/kill-switch", "/api/admin/live/users/5/kill-switch")) {
            // An ordinary admin access token is refused at the edge ...
            mvc.perform(put(path).header("Authorization", admin)).andExpect(status().isForbidden());
            // ... as is a trader's step-up token ...
            mvc.perform(put(path).header("Authorization", userStepUp)).andExpect(status().isForbidden());
            mvc.perform(put(path)).andExpect(status().isUnauthorized());
            // ... an admin step-up token is routed (the live service re-checks age, session and MFA).
            mvc.perform(put(path).header("Authorization", adminStepUp))
                    .andExpect(forwardedUrl("/fallback/live-trading-service"));
        }
        // Reading the halt state still uses the ordinary admin token; the step-up token is not an access token.
        mvc.perform(get("/api/admin/live/kill-switch").header("Authorization", adminStepUp)).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/live/users/5").header("Authorization", adminStepUp)).andExpect(status().isForbidden());
    }

    @Test
    void swaggerUiIsServedAndListsEveryServiceDocumentThroughTheGateway() throws Exception {
        // Regression: springdoc.api-docs.enabled=false used to remove the whole Swagger UI (404).
        mvc.perform(get("/swagger-ui.html")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
        mvc.perform(get("/v3/api-docs/gateway/swagger-config")).andExpect(status().isOk())
                .andExpect(jsonPath("$.urls.length()").value(6))
                .andExpect(jsonPath("$.urls[*].url").value(org.hamcrest.Matchers.containsInAnyOrder(
                        "/v3/api-docs/user", "/v3/api-docs/market-data", "/v3/api-docs/order",
                        "/v3/api-docs/portfolio", "/v3/api-docs/learning", "/v3/api-docs/live-trading")));
        // Each document is proxied to its own service (no instance runs in this test, so the lookup fails).
        assertThatThrownBy(() -> mvc.perform(get("/v3/api-docs/live-trading")))
                .hasMessageContaining("tradeflow-live-trading-service");
        assertThatThrownBy(() -> mvc.perform(get("/v3/api-docs/learning")))
                .hasMessageContaining("tradeflow-learning-service");
    }

    @Test
    void refusesToStartWithoutPublicKeys() {
        assertThatThrownBy(() -> new GatewaySecurityConfig().jwtDecoder("", "tradeflow-user-service"))
                .hasMessageContaining("public-keys is not set");
    }
}
