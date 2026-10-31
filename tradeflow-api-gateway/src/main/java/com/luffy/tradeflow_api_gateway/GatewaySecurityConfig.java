package com.luffy.tradeflow_api_gateway;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authorization.AuthorityAuthorizationManager;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationManagers;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

import java.io.IOException;

/**
 * Edge security. Requests are checked here first (valid JWT, role for admin paths) so
 * bad traffic never reaches a service; the Authorization header is forwarded unchanged
 * and every service validates it again (defence in depth). /internal/** is never
 * reachable from outside. Anything not explicitly routed is denied.
 */
@Configuration
public class GatewaySecurityConfig {

    /**
     * RS256 only, keys from user-service's public key ring (the gateway cannot issue tokens),
     * issuer and audience checked. Step-up tokens (audience tradeflow-live) are also accepted
     * here so they can reach /api/live/**; the live service enforces which token each action needs.
     */
    @Bean
    public JwtDecoder jwtDecoder(@Value("${tradeflow.security.jwt.public-keys:}") String publicKeys,
                                 @Value("${tradeflow.security.jwt.issuer:tradeflow-user-service}") String issuer) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder
                .withJwkSource(new com.nimbusds.jose.jwk.source.ImmutableJWKSet<>(GatewayKeys.load(publicKeys)))
                .jwsAlgorithm(org.springframework.security.oauth2.jose.jws.SignatureAlgorithm.RS256)
                .build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithValidators(java.util.List.of(
                new org.springframework.security.oauth2.jwt.JwtIssuerValidator(issuer),
                new org.springframework.security.oauth2.jwt.JwtClaimValidator<java.util.List<String>>("aud",
                        aud -> aud != null && (aud.contains("tradeflow") || aud.contains("tradeflow-live"))))));
        return decoder;
    }

    @Bean
    public SecurityFilterChain gatewaySecurity(HttpSecurity http) throws Exception {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);

        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/internal/**").denyAll()
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/fallback/**", "/error",
                                "/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login",
                                "/api/auth/login/mfa", "/api/auth/refresh", "/api/auth/logout").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/market/**", "/api/demo/market/**").permitAll()
                        // Live actions that place orders or link/unlink a broker need a step-up token
                        // (live-trading-service checks it again, plus its age, readiness and limits).
                        .requestMatchers(HttpMethod.POST, "/api/live/orders/**", "/api/live/broker/**")
                        .access(audience(STEP_UP_AUDIENCE))
                        .requestMatchers("/api/live/**").authenticated()
                        // Everywhere else only ordinary access tokens: a step-up token is for live trading only.
                        // Readiness reviewers (not necessarily admins) handle review cases.
                        .requestMatchers("/api/admin/readiness/reviews/**").access(AuthorizationManagers.allOf(
                                AuthorityAuthorizationManager.hasAnyRole("READINESS_REVIEWER", "ADMIN"),
                                audience(ACCESS_AUDIENCE)))
                        // Changing a live kill switch needs the administrator's own step-up token.
                        .requestMatchers(HttpMethod.PUT, "/api/admin/live/kill-switch",
                                "/api/admin/live/users/*/kill-switch").access(AuthorizationManagers.allOf(
                                AuthorityAuthorizationManager.hasRole("ADMIN"), audience(STEP_UP_AUDIENCE)))
                        .requestMatchers("/api/admin/**").access(AuthorizationManagers.allOf(
                                AuthorityAuthorizationManager.hasRole("ADMIN"), audience(ACCESS_AUDIENCE)))
                        .requestMatchers("/api/**").access(audience(ACCESS_AUDIENCE))
                        .anyRequest().denyAll())
                .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(converter))
                        .authenticationEntryPoint((req, res, ex) -> problem(res, 401, "UNAUTHENTICATED",
                                "A valid bearer token is required")))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> problem(res, 401, "UNAUTHENTICATED",
                                "A valid bearer token is required"))
                        .accessDeniedHandler((req, res, ex) -> problem(res, 403, "FORBIDDEN",
                                "You are not allowed to perform this action")))
                .build();
    }

    static final String ACCESS_AUDIENCE = "tradeflow";
    static final String STEP_UP_AUDIENCE = "tradeflow-live";

    /** Authenticated with a JWT whose audience includes {@code audience}. */
    static AuthorizationManager<RequestAuthorizationContext> audience(String audience) {
        return (authentication, context) -> {
            Authentication a = authentication.get();
            return new AuthorizationDecision(a instanceof JwtAuthenticationToken jwt && a.isAuthenticated()
                    && jwt.getToken().getAudience() != null && jwt.getToken().getAudience().contains(audience));
        };
    }

    static void problem(HttpServletResponse response, int status, String code, String detail) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write("{\"type\":\"about:blank\",\"status\":" + status + ",\"code\":\"" + code
                + "\",\"detail\":\"" + detail + "\"}");
    }
}
