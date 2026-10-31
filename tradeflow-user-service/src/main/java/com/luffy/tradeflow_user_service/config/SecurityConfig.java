package com.luffy.tradeflow_user_service.config;

import com.luffy.tradeflow.common.security.JwtSecurityConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtSecurityConfig jwtSecurity) throws Exception {
        jwtSecurity.applyDefaults(http)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(JwtSecurityConfig.PUBLIC_PATHS).permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login",
                                "/api/auth/login/mfa", "/api/auth/refresh", "/api/auth/logout").permitAll()
                        // Authenticated by its own client-credentials check; never routed by the gateway.
                        .requestMatchers(HttpMethod.POST, "/internal/auth/service-token").permitAll()
                        // Allow anyone to reach the H2 console without logging in (h2 profile only).
                        .requestMatchers("/h2-console/**").permitAll()
                        .anyRequest().authenticated()
                )
                // H2 console renders itself inside an HTML frame; allow that
                // for requests from the same origin only.
                .headers(headers -> headers
                        .frameOptions(frame -> frame.sameOrigin())
                );

        return http.build();
    }
}
