package com.luffy.tradeflow_api_gateway;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
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
                        // Readiness reviewers (not necessarily admins) handle review cases.
                        .requestMatchers("/api/admin/readiness/reviews/**").hasAnyRole("READINESS_REVIEWER", "ADMIN")
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/**").authenticated()
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

    static void problem(HttpServletResponse response, int status, String code, String detail) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write("{\"type\":\"about:blank\",\"status\":" + status + ",\"code\":\"" + code
                + "\",\"detail\":\"" + detail + "\"}");
    }
}
