package com.luffy.tradeflow_portfolio_service.assistant;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;

@Slf4j
@Configuration
@EnableConfigurationProperties(AssistantProperties.class)
public class AssistantConfig {

    /**
     * Registers Claude only when explicitly enabled and a key is present. Otherwise no
     * LlmProvider bean exists and the assistant answers deterministically.
     */
    @Bean
    public LlmProvider llmProvider(AssistantProperties properties, JsonMapper jsonMapper, Clock clock,
                                   @Value("${tradeflow.ai.base-url:#{null}}") String baseUrl) {
        if (!properties.anthropicEnabled()) {
            log.info("AI assistant: provider '{}' - deterministic answers only", properties.provider());
            return null;
        }
        if (properties.apiKey() == null || properties.apiKey().isBlank()) {
            log.warn("AI assistant: TRADEFLOW_AI_PROVIDER=anthropic but ANTHROPIC_API_KEY is not set - "
                    + "falling back to deterministic answers");
            return null;
        }
        log.info("AI assistant: Anthropic model {}", properties.model());
        return new AnthropicLlmProvider(properties, jsonMapper, clock, baseUrl);
    }
}
