package com.luffy.tradeflow_portfolio_service.assistant;

import com.luffy.tradeflow_portfolio_service.assistant.AssistantModels.Fact;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Provider-neutral contract for the assistant's language model. A provider gets
 * the question and a tool executor bound to the current user; it must only use
 * that executor to obtain data.
 */
public interface LlmProvider {

    String name();

    String model();

    LlmAnswer answer(String question, BiFunction<String, Map<String, Object>, Fact> tools);

    record LlmAnswer(String text, List<Fact> facts, int toolRounds, long inputTokens, long outputTokens) {
    }

    /** The model could not produce an answer; the caller falls back to deterministic analytics. */
    class LlmUnavailableException extends RuntimeException {
        public LlmUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }

        public LlmUnavailableException(String message) {
            super(message);
        }
    }
}
