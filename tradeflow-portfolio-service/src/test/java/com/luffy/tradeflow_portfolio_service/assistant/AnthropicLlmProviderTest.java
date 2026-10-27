package com.luffy.tradeflow_portfolio_service.assistant;

import com.luffy.tradeflow_portfolio_service.assistant.AssistantModels.Fact;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs the real SDK against a local fake of POST /v1/messages, so the tool loop,
 * request shape and error mapping are verified without credentials or cost.
 */
class AnthropicLlmProviderTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private HttpServer server;
    private final ConcurrentLinkedQueue<String> responses = new ConcurrentLinkedQueue<>();
    private final List<JsonNode> requests = new ArrayList<>();
    private volatile int status = 200;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            requests.add(JSON.readTree(exchange.getRequestBody().readAllBytes()));
            String body = status == 200 ? responses.poll()
                    : "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private AnthropicLlmProvider provider() {
        AssistantProperties props = new AssistantProperties("anthropic", "test-key", null, null,
                Duration.ofSeconds(5), Duration.ofSeconds(20), 2, 10);
        return new AnthropicLlmProvider(props, JSON, Clock.systemUTC(),
                "http://localhost:" + server.getAddress().getPort());
    }

    private static String message(String stopReason, String content) {
        return "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-opus-5-5\","
                + "\"content\":" + content + ",\"stop_reason\":\"" + stopReason + "\",\"stop_sequence\":null,"
                + "\"usage\":{\"input_tokens\":120,\"output_tokens\":30}}";
    }

    @Test
    void runsToolLoopAndReturnsFinalText() {
        responses.add(message("tool_use", "[{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"sig-1\"},"
                + "{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"get_portfolio_summary\",\"input\":{}}]"));
        responses.add(message("end_turn", "[{\"type\":\"text\",\"text\":\"Your portfolio is worth ₹10,200.00.\"}]"));
        List<String> calledTools = new ArrayList<>();

        var answer = provider().answer("What is my portfolio worth?", (tool, input) -> {
            calledTools.add(tool);
            return new Fact(tool, input, "CALCULATED", Map.of("totalValue", 10200.00));
        });

        assertThat(answer.text()).isEqualTo("Your portfolio is worth ₹10,200.00.");
        assertThat(answer.toolRounds()).isEqualTo(1);
        assertThat(answer.inputTokens()).isEqualTo(240);
        assertThat(calledTools).containsExactly("get_portfolio_summary");

        JsonNode first = requests.get(0);
        assertThat(first.get("model").asString()).isEqualTo("claude-opus-5-5");
        assertThat(first.at("/output_config/effort").asString()).isEqualTo("low");
        assertThat(first.get("fallbacks").asString()).isEqualTo("default");
        assertThat(first.get("tools").size()).isEqualTo(AssistantToolbox.TOOLS.size());
        assertThat(first.get("system").asString()).contains("read-only");

        JsonNode second = requests.get(1).get("messages");
        assertThat(second.size()).isEqualTo(3);
        assertThat(second.get(1).at("/content/0/type").asString()).isEqualTo("thinking"); // echoed unchanged
        assertThat(second.get(1).at("/content/0/signature").asString()).isEqualTo("sig-1");
        JsonNode toolResult = second.get(2).at("/content/0");
        assertThat(toolResult.get("type").asString()).isEqualTo("tool_result");
        assertThat(toolResult.get("tool_use_id").asString()).isEqualTo("toolu_1");
        assertThat(toolResult.get("content").asString()).contains("CALCULATED").contains("10200");
    }

    @Test
    void refusalAndTruncationAreUnavailableNotAnswers() {
        responses.add(message("refusal", "[]"));
        assertThatThrownBy(() -> provider().answer("q", (t, i) -> null))
                .isInstanceOf(LlmProvider.LlmUnavailableException.class).hasMessageContaining("declined");
        responses.add(message("max_tokens", "[{\"type\":\"text\",\"text\":\"Your portf\"}]"));
        assertThatThrownBy(() -> provider().answer("q", (t, i) -> null))
                .isInstanceOf(LlmProvider.LlmUnavailableException.class).hasMessageContaining("incomplete");
    }

    @Test
    void endlessToolUseIsCappedByMaxRounds() {
        String toolUse = message("tool_use",
                "[{\"type\":\"tool_use\",\"id\":\"toolu_x\",\"name\":\"get_risk_report\",\"input\":{}}]");
        for (int i = 0; i < 5; i++) {
            responses.add(toolUse);
        }
        assertThatThrownBy(() -> provider().answer("q", (t, i) -> new Fact(t, i, "CALCULATED", Map.of())))
                .isInstanceOf(LlmProvider.LlmUnavailableException.class).hasMessageContaining("tool rounds");
        assertThat(requests).hasSize(3); // maxToolRounds = 2 → initial call + 2 tool rounds
    }

    @Test
    void providerErrorsAreMappedToUnavailable() {
        status = 529;
        assertThatThrownBy(() -> provider().answer("q", (t, i) -> null))
                .isInstanceOf(LlmProvider.LlmUnavailableException.class).hasMessageContaining("overloaded");
    }
}
