package com.luffy.tradeflow_portfolio_service.assistant;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.fasterxml.jackson.core.type.TypeReference;
import com.luffy.tradeflow_portfolio_service.assistant.AssistantModels.Fact;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

/**
 * Claude via the official Anthropic Java SDK, with a manual tool-use loop over the
 * read-only {@link AssistantToolbox} tools. Usage is kept small: low effort, a
 * capped number of tool rounds, one SDK retry, per-call timeouts and an overall
 * deadline. Any failure surfaces as {@link LlmUnavailableException} so the
 * assistant falls back to deterministic analytics instead of failing.
 */
@Slf4j
public class AnthropicLlmProvider implements LlmProvider {

    static final String SYSTEM_PROMPT = """
            You are the TradeFlow Portfolio Assistant inside a simulated trading application. Answer the \
            user's question about their own TradeFlow account using only data returned by the tools.

            - Take every number (prices, balances, P&L, weights, returns) from a tool result in this \
            conversation. If the tools don't provide something, say it isn't available rather than estimating \
            it or using outside knowledge of real markets or news.
            - TradeFlow prices come from a seeded random walk. Say so when you mention prices or returns, and \
            never present them as real market data.
            - When you calculate something yourself from tool data, say it is your calculation and show the inputs.
            - To define or explain a metric, call explain_metric and use its definition.
            - You are read-only: you cannot place, change or cancel orders or move money. If asked, say so.
            - Do not give personalised buy or sell recommendations; you may explain metrics, risks and assumptions.
            - Amounts are in INR. Keep answers short: a few sentences or a compact list.""";

    /** Distinct tool executions allowed per question (repeats are served from cache). */
    static final int MAX_TOOL_CALLS = 8;
    /** Tool results longer than this are truncated before being sent to the model. */
    static final int MAX_TOOL_RESULT_CHARS = 12_000;

    private final AnthropicClient client;
    private final AssistantProperties properties;
    private final JsonMapper jsonMapper;
    private final Clock clock;
    private final List<Tool> tools;

    public AnthropicLlmProvider(AssistantProperties properties, JsonMapper jsonMapper, Clock clock, String baseUrl) {
        AnthropicOkHttpClient.Builder builder = AnthropicOkHttpClient.builder()
                .apiKey(properties.apiKey())
                .timeout(properties.requestTimeout())
                .maxRetries(1);
        if (baseUrl != null) {
            builder.baseUrl(baseUrl);
        }
        this.client = builder.build();
        this.properties = properties;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
        this.tools = AssistantToolbox.TOOLS.stream().map(AnthropicLlmProvider::toTool).toList();
    }

    @Override
    public String name() {
        return "anthropic";
    }

    @Override
    public String model() {
        return properties.model();
    }

    @Override
    public LlmAnswer answer(String question, BiFunction<String, Map<String, Object>, Fact> executor) {
        Instant deadline = clock.instant().plus(properties.overallDeadline());
        MessageCreateParams.Builder request = MessageCreateParams.builder()
                .model(properties.model())
                .maxTokens(properties.maxTokens())
                .system(SYSTEM_PROMPT)
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
                // Server-side refusal fallback: a declined request is retried on a fallback model chosen by
                // the API instead of ending with no answer.
                .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                .addUserMessage(question);
        tools.forEach(request::addTool);

        List<Fact> facts = new ArrayList<>();
        Map<String, String> resultCache = new java.util.HashMap<>();
        int[] toolCalls = {0};
        long inputTokens = 0;
        long outputTokens = 0;
        for (int round = 0; round <= properties.maxToolRounds(); round++) {
            if (clock.instant().isAfter(deadline)) {
                throw new LlmUnavailableException("assistant deadline exceeded");
            }
            Message response = call(request.build());
            inputTokens += response.usage().inputTokens();
            outputTokens += response.usage().outputTokens();
            StopReason stop = response.stopReason().orElse(null);

            if (StopReason.TOOL_USE.equals(stop)) {
                if (round == properties.maxToolRounds()) {
                    throw new LlmUnavailableException("model needed more than " + round + " tool rounds");
                }
                List<ContentBlockParam> results = new ArrayList<>();
                for (ContentBlock block : response.content()) {
                    block.toolUse().ifPresent(use -> results.add(runTool(use, executor, facts, resultCache,
                            toolCalls)));
                }
                // Append the assistant turn unchanged (including thinking blocks), then the tool results
                // in a single user message.
                request.addMessage(response).addUserMessageOfBlockParams(results);
                continue;
            }
            if (StopReason.REFUSAL.equals(stop)) {
                throw new LlmUnavailableException("the model declined to answer");
            }
            String text = response.content().stream()
                    .flatMap(b -> b.text().stream())
                    .map(t -> t.text())
                    .collect(Collectors.joining("\n")).trim();
            if (StopReason.MAX_TOKENS.equals(stop) || text.isEmpty()) {
                throw new LlmUnavailableException("model response was incomplete (" + stop + ")");
            }
            log.info("Assistant answered with {} tool round(s), {} input / {} output tokens", round, inputTokens,
                    outputTokens);
            return new LlmAnswer(text, facts, round, inputTokens, outputTokens);
        }
        throw new LlmUnavailableException("tool loop did not finish");
    }

    private Message call(MessageCreateParams params) {
        try {
            return client.messages().create(params);
        } catch (AnthropicServiceException e) {
            String reason = switch (e.statusCode()) {
                case 401, 403 -> "the API key was rejected";
                case 429 -> "the provider rate limit was reached";
                case 529 -> "the provider is overloaded";
                default -> "provider returned HTTP " + e.statusCode();
            };
            throw new LlmUnavailableException(reason, e);
        } catch (AnthropicIoException e) {
            throw new LlmUnavailableException("could not reach the provider (" + e.getMessage() + ")", e);
        }
    }

    private ContentBlockParam runTool(ToolUseBlock use, BiFunction<String, Map<String, Object>, Fact> executor,
                                      List<Fact> facts, Map<String, String> cache, int[] toolCalls) {
        Map<String, Object> input;
        try {
            input = use._input().convert(new TypeReference<Map<String, Object>>() { });
        } catch (RuntimeException e) {
            return toolError(use, "Malformed tool input: expected a JSON object");
        }
        if (input == null) {
            input = Map.of();
        }
        String cacheKey = use.name() + jsonMapper.writeValueAsString(new java.util.TreeMap<>(input));
        String cached = cache.get(cacheKey);
        if (cached != null) {
            // Identical repeated call: answer from this question's cache instead of hitting services again.
            return ContentBlockParam.ofToolResult(ToolResultBlockParam.builder().toolUseId(use.id())
                    .content(cached).build());
        }
        if (++toolCalls[0] > MAX_TOOL_CALLS) {
            return toolError(use, "Tool-call budget for this question is exhausted; answer with the data you have");
        }
        Fact fact = executor.apply(use.name(), input);
        facts.add(fact);
        String content = jsonMapper.writeValueAsString(Map.of("kind", fact.kind(), "data", fact.data()));
        if (content.length() > MAX_TOOL_RESULT_CHARS) {
            content = content.substring(0, MAX_TOOL_RESULT_CHARS) + " …[truncated to keep the request small]";
        }
        cache.put(cacheKey, content);
        return ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                .toolUseId(use.id())
                .content(content)
                .isError("ERROR".equals(fact.kind()))
                .build());
    }

    private static ContentBlockParam toolError(ToolUseBlock use, String message) {
        return ContentBlockParam.ofToolResult(ToolResultBlockParam.builder().toolUseId(use.id())
                .content("{\"kind\":\"ERROR\",\"data\":{\"error\":\"" + message + "\"}}").isError(true).build());
    }

    private static Tool toTool(AssistantToolbox.ToolSpec spec) {
        Tool.InputSchema.Properties.Builder props = Tool.InputSchema.Properties.builder();
        spec.properties().forEach((k, v) -> props.putAdditionalProperty(k, JsonValue.from(v)));
        return Tool.builder()
                .name(spec.name())
                .description(spec.description())
                .inputSchema(Tool.InputSchema.builder()
                        .properties(props.build())
                        .required(spec.required())
                        .build())
                .build();
    }
}
