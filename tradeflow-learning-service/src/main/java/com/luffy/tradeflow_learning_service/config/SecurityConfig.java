package com.luffy.tradeflow_learning_service.config;

import com.luffy.tradeflow.common.security.JwtSecurityConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
                        // Eligibility for live trading: other services only (SERVICE token), never via the gateway.
                        .requestMatchers("/internal/**").hasRole("SERVICE")
                        .requestMatchers("/api/admin/readiness/reviews/**").hasAnyRole("READINESS_REVIEWER", "ADMIN")
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated());
        return http.build();
    }
}
