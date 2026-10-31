package com.luffy.tradeflow.common.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * Stateless JWT resource-server setup shared by every service. Each service still
 * declares its own {@code SecurityFilterChain} with its URL rules and calls
 * {@link #applyDefaults(HttpSecurity)} for the common parts.
 */
@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class JwtSecurityConfig implements WebMvcConfigurer {

    /** Endpoints that never need a token in any service. */
    public static final String[] PUBLIC_PATHS = {
            "/actuator/health/**", "/actuator/info", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html",
            "/error"
    };

    private final ProblemDetailSecurityHandler problemHandler;
    private final JwtAuthenticationConverter jwtAuthenticationConverter;

    public JwtSecurityConfig(ProblemDetailSecurityHandler problemHandler) {
        this.problemHandler = problemHandler;
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("ROLE_");
        this.jwtAuthenticationConverter = new JwtAuthenticationConverter();
        this.jwtAuthenticationConverter.setJwtGrantedAuthoritiesConverter(authorities);
    }

    @Bean
    public JwtDecoder jwtDecoder(JwtProperties properties) {
        // tradeflow.security.jwt.audience may list several (comma-separated), e.g. the live service.
        return decoder(PublicKeyRing.load(properties.publicKeys()), properties.issuer(),
                java.util.Arrays.stream(properties.audience().split(",")).map(String::trim).toList());
    }

    /**
     * RS256 only (an HS256 or "none" token is rejected regardless of its content), known key id,
     * issuer, audience, and the standard expiry/not-before checks.
     */
    public static NimbusJwtDecoder decoder(com.nimbusds.jose.jwk.JWKSet keys, String issuer, String audience) {
        return decoder(keys, issuer, List.of(audience));
    }

    /** Accepts a token whose {@code aud} contains any of {@code audiences}. */
    public static NimbusJwtDecoder decoder(com.nimbusds.jose.jwk.JWKSet keys, String issuer, List<String> audiences) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder
                .withJwkSource(new com.nimbusds.jose.jwk.source.ImmutableJWKSet<>(keys))
                .jwsAlgorithm(org.springframework.security.oauth2.jose.jws.SignatureAlgorithm.RS256)
                .build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithValidators(List.of(
                new org.springframework.security.oauth2.jwt.JwtIssuerValidator(issuer),
                new org.springframework.security.oauth2.jwt.JwtClaimValidator<List<String>>("aud",
                        aud -> aud != null && aud.stream().anyMatch(audiences::contains)))));
        return decoder;
    }

    /** Stateless sessions, no CSRF (bearer tokens only; no cookie authenticates these APIs), JWT auth, JSON errors. */
    public HttpSecurity applyDefaults(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .oauth2ResourceServer(o -> o
                        .jwt(j -> j.jwtAuthenticationConverter(jwtAuthenticationConverter))
                        .authenticationEntryPoint(problemHandler))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(problemHandler)
                        .accessDeniedHandler(problemHandler));
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new AuthenticatedUserArgumentResolver());
    }

    static final class AuthenticatedUserArgumentResolver implements HandlerMethodArgumentResolver {

        @Override
        public boolean supportsParameter(MethodParameter parameter) {
            return AuthenticatedUser.class.equals(parameter.getParameterType());
        }

        @Override
        public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                      NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
                return AuthenticatedUser.from(jwt);
            }
            throw new org.springframework.security.authentication.AuthenticationCredentialsNotFoundException(
                    "A bearer token is required");
        }
    }
}
