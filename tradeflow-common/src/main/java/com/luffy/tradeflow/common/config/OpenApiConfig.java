package com.luffy.tradeflow.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Swagger UI with an "Authorize" button that accepts the JWT from POST /api/auth/login.
 * <p>
 * The server is the relative URL "/": the documents are read through the API gateway's Swagger UI,
 * so "Try it out" calls go through the gateway (same origin) instead of a container address the
 * browser cannot reach and that would bypass the gateway's checks.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI tradeflowOpenApi(@Value("${spring.application.name}") String name,
                                    @Value("${tradeflow.environment:PLATFORM}") String environment) {
        String description = switch (environment) {
            case "DEMO" -> "TradeFlow DEMO environment: simulated trading with virtual money. All prices are simulated.";
            case "LIVE" -> "TradeFlow LIVE environment: real-money trading through a broker. No broker is integrated "
                    + "yet, so broker calls answer 503 BROKER_NOT_CONFIGURED. State-changing calls need a step-up "
                    + "token (POST /api/auth/step-up).";
            default -> "TradeFlow platform service (accounts, learning and readiness); shared by DEMO and LIVE.";
        };
        return new OpenAPI()
                .info(new Info().title(name + " [" + environment + "]").version("v1").description(description))
                .servers(List.of(new Server().url("/").description("Through the API gateway")))
                .components(new Components().addSecuritySchemes("bearer",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearer"));
    }
}
