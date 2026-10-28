package com.luffy.tradeflow.common.config;

import feign.RequestInterceptor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Feign calls made while serving a user request carry that user's JWT, so the called
 * service applies the same user's authorization (token relay). Calls that set their
 * own Authorization header (service tokens) are left alone.
 */
@Configuration
@ConditionalOnClass(name = "feign.RequestInterceptor")
public class FeignTokenRelayConfig {

    @Bean
    public RequestInterceptor bearerTokenRelay() {
        return template -> {
            if (template.headers().containsKey(HttpHeaders.AUTHORIZATION)) {
                return;
            }
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth instanceof JwtAuthenticationToken jwt) {
                template.header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt.getToken().getTokenValue());
            }
        };
    }
}
