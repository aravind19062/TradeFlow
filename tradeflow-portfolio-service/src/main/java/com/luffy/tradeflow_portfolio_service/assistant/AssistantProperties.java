package com.luffy.tradeflow_portfolio_service.assistant;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param provider              {@code none} (deterministic answers only) or {@code anthropic}
 * @param apiKey                Anthropic API key; read from ANTHROPIC_API_KEY, never committed
 * @param model                 Claude model id
 * @param maxTokens             cap per model response (thinking + answer)
 * @param requestTimeout        HTTP timeout for one model call
 * @param overallDeadline       wall-clock budget for one question including tool rounds
 * @param maxToolRounds         maximum model ↔ tool round trips per question
 * @param maxQuestionsPerHour   per-user LLM budget; beyond it answers are deterministic
 */
@ConfigurationProperties("tradeflow.ai")
public record AssistantProperties(String provider, String apiKey, String model, Long maxTokens,
                                  Duration requestTimeout, Duration overallDeadline, Integer maxToolRounds,
                                  Integer maxQuestionsPerHour) {

    public AssistantProperties {
        provider = provider == null || provider.isBlank() ? "none" : provider.trim().toLowerCase();
        model = model == null || model.isBlank() ? "claude-opus-5-5" : model;
        maxTokens = maxTokens == null ? 8000L : maxTokens;
        requestTimeout = requestTimeout == null ? Duration.ofSeconds(30) : requestTimeout;
        overallDeadline = overallDeadline == null ? Duration.ofSeconds(60) : overallDeadline;
        maxToolRounds = maxToolRounds == null ? 4 : maxToolRounds;
        maxQuestionsPerHour = maxQuestionsPerHour == null ? 30 : maxQuestionsPerHour;
    }

    public boolean anthropicEnabled() {
        return "anthropic".equals(provider);
    }
}
