package com.luffy.tradeflow_portfolio_service.assistant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

public final class AssistantModels {

    private AssistantModels() {
    }

    public record AskRequest(@NotBlank @Size(max = 500) String question) {
    }

    /**
     * How the answer was produced. LLM: Claude wrote {@code answer} from the facts.
     * DETERMINISTIC: a template filled with the facts; no AI involved.
     * REFUSED: the request asked for something the assistant must not do.
     */
    public enum Mode { LLM, DETERMINISTIC, REFUSED }

    /**
     * A piece of application data the answer is based on.
     *
     * @param kind RETRIEVED (stored data), CALCULATED (computed by TradeFlow), SIMULATED_MARKET_DATA,
     *             REFERENCE (fixed methodology text) or ERROR
     */
    public record Fact(String tool, Map<String, Object> input, String kind, Object data) {
    }

    /**
     * @param answer      the text shown to the user
     * @param answerKind  AI_GENERATED_EXPLANATION or DETERMINISTIC_TEMPLATE
     * @param notice      why the deterministic path was used, if it was a fallback
     */
    public record AskResponse(String question, Mode mode, String answerKind, String answer, String provider,
                              String model, List<Fact> facts, String notice, List<String> disclaimers) {
    }

    public static final List<String> DISCLAIMERS = List.of(
            "All market prices in TradeFlow are simulated demo data, not live market data.",
            "This is not investment advice. The assistant is read-only and cannot place, change or cancel orders.");
}
