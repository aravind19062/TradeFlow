package com.luffy.tradeflow_portfolio_service.assistant;

import com.luffy.tradeflow.common.security.AuthenticatedUser;
import com.luffy.tradeflow_portfolio_service.assistant.AssistantModels.AskResponse;
import com.luffy.tradeflow_portfolio_service.assistant.AssistantModels.Mode;
import com.luffy.tradeflow_portfolio_service.assistant.DeterministicAssistant.Intent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Chooses how to answer: refuse action requests outright, use the LLM when one is
 * configured and the user is within budget, and otherwise (or on any LLM failure)
 * answer from deterministic analytics, saying so in {@code notice}.
 */
@Slf4j
@Service
public class AssistantService {

    private final AssistantToolbox toolbox;
    private final DeterministicAssistant deterministic;
    private final LlmProvider llm;
    private final AssistantProperties properties;
    private final Clock clock;
    private final Map<Long, Deque<Instant>> usage = new ConcurrentHashMap<>();

    public AssistantService(AssistantToolbox toolbox, DeterministicAssistant deterministic,
                            ObjectProvider<LlmProvider> llm, AssistantProperties properties, Clock clock) {
        this.toolbox = toolbox;
        this.deterministic = deterministic;
        this.llm = llm.getIfAvailable();
        this.properties = properties;
        this.clock = clock;
    }

    public AskResponse ask(AuthenticatedUser user, String question) {
        String q = question.trim();
        Intent intent = deterministic.classify(q);
        if (intent == Intent.ACTION_REQUEST) {
            return new AskResponse(q, Mode.REFUSED, "DETERMINISTIC_TEMPLATE", DeterministicAssistant.ACTION_REFUSAL,
                    "none", null, java.util.List.of(), null, AssistantModels.DISCLAIMERS);
        }
        String notice = null;
        if (llm == null) {
            notice = "No AI provider is configured (TRADEFLOW_AI_PROVIDER=none); this answer was built from "
                    + "templates and TradeFlow data.";
        } else if (!withinBudget(user.id())) {
            notice = "You reached the hourly AI question limit (" + properties.maxQuestionsPerHour()
                    + "); this answer was built from templates and TradeFlow data.";
        } else {
            try {
                LlmProvider.LlmAnswer answer = llm.answer(q, (tool, input) -> toolbox.execute(user, tool, input));
                return new AskResponse(q, Mode.LLM, "AI_GENERATED_EXPLANATION", answer.text(), llm.name(),
                        llm.model(), answer.facts(), null, AssistantModels.DISCLAIMERS);
            } catch (LlmProvider.LlmUnavailableException | IllegalStateException e) {
                log.warn("AI provider unavailable, answering deterministically: {}", e.getMessage());
                notice = "The AI provider could not answer (" + e.getMessage() + "); this answer was built from "
                        + "templates and TradeFlow data instead.";
            }
        }
        DeterministicAssistant.Result result = deterministic.answer(q, intent, user, toolbox);
        return new AskResponse(q, Mode.DETERMINISTIC, "DETERMINISTIC_TEMPLATE", result.answer(), "none", null,
                result.facts(), notice, AssistantModels.DISCLAIMERS);
    }

    public Map<String, Object> capabilities() {
        return Map.of(
                "provider", llm == null ? "none" : llm.name(),
                "model", llm == null ? "" : llm.model(),
                "llmEnabled", llm != null,
                "tools", AssistantToolbox.TOOLS.stream().map(AssistantToolbox.ToolSpec::name).toList(),
                "readOnly", true,
                "examples", DeterministicAssistant.HELP_TEXT.lines().skip(1).map(String::strip).toList());
    }

    /** Sliding one-hour window per user; protects the API budget. */
    private boolean withinBudget(long userId) {
        Instant now = clock.instant();
        Deque<Instant> calls = usage.computeIfAbsent(userId, id -> new ArrayDeque<>());
        synchronized (calls) {
            while (!calls.isEmpty() && calls.peekFirst().isBefore(now.minus(Duration.ofHours(1)))) {
                calls.pollFirst();
            }
            if (calls.size() >= properties.maxQuestionsPerHour()) {
                return false;
            }
            calls.addLast(now);
            return true;
        }
    }
}
