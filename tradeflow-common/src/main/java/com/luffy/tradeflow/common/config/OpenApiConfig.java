package com.luffy.tradeflow.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Swagger UI with an "Authorize" button that accepts the JWT from POST /api/auth/login. */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI tradeflowOpenApi(@Value("${spring.application.name}") String name) {
        return new OpenAPI()
                .info(new Info().title(name).version("v1")
                        .description("TradeFlow simulated trading platform. All prices are simulated demo data."))
                .components(new Components().addSecuritySchemes("bearer",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearer"));
    }
}
