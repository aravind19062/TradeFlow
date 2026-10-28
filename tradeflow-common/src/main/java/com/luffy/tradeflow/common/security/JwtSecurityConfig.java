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
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
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

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
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

    /** Prefix of the public placeholder secret that only the local profile may use. */
    public static final String LOCAL_PLACEHOLDER_PREFIX = "local-dev-only";

    @Bean
    public SecretKey jwtSigningKey(JwtProperties properties, org.springframework.core.env.Environment environment) {
        if (properties.secret().startsWith(LOCAL_PLACEHOLDER_PREFIX)) {
            boolean localOrTest = environment.acceptsProfiles(
                    org.springframework.core.env.Profiles.of("local", "test"));
            if (!localOrTest) {
                throw new IllegalStateException("Refusing to start: the public local-development JWT secret is "
                        + "configured outside the 'local' profile. Set TRADEFLOW_JWT_SECRET to a private value "
                        + "(e.g. `openssl rand -base64 48`).");
            }
            org.slf4j.LoggerFactory.getLogger(JwtSecurityConfig.class).warn(
                    "Using the public local-development JWT secret ('local' profile). Never use this profile on a "
                            + "shared or internet-facing environment.");
        }
        return new SecretKeySpec(properties.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    @Bean
    public JwtDecoder jwtDecoder(SecretKey jwtSigningKey, JwtProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtSigningKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.issuer()));
        return decoder;
    }

    /** Signs user tokens (user-service) and short-lived service tokens (all services). */
    @Bean
    public org.springframework.security.oauth2.jwt.JwtEncoder jwtEncoder(SecretKey jwtSigningKey) {
        return new org.springframework.security.oauth2.jwt.NimbusJwtEncoder(
                new com.nimbusds.jose.jwk.source.ImmutableSecret<>(jwtSigningKey));
    }

    /** Stateless sessions, no CSRF (no cookies are used), JWT auth and JSON error bodies. */
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
