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
    void refusesToStartWithoutPublicKeys() {
        assertThatThrownBy(() -> new GatewaySecurityConfig().jwtDecoder("", "tradeflow-user-service"))
                .hasMessageContaining("public-keys is not set");
    }
}
