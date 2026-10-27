package com.luffy.tradeflow_portfolio_service.assistant;

import com.luffy.tradeflow.common.events.EventCodec;
import com.luffy.tradeflow.common.events.Events;
import com.luffy.tradeflow.common.events.Topics;
import com.luffy.tradeflow.common.testsupport.TestJwts;
import com.luffy.tradeflow.common.web.ApiException;
import com.luffy.tradeflow_portfolio_service.client.MarketDataClient;
import com.luffy.tradeflow_portfolio_service.client.OrderServiceClient;
import com.luffy.tradeflow_portfolio_service.messaging.PortfolioEventListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AssistantIntegrationTest {

    abstract static class Base {
        static final long USER = 7001;

        @Autowired MockMvc mvc;
        @Autowired PortfolioEventListener listener;
        @Autowired EventCodec codec;
        @MockitoBean MarketDataClient marketData;
        @MockitoBean OrderServiceClient orders;

        @BeforeEach
        void account() {
            listener.onUserRegistered(codec.encode(codec.wrap(Topics.USERS_REGISTERED, String.valueOf(USER),
                    new Events.UserRegistered(USER, "a@test.dev", "A"))));
            when(marketData.quotes(anyCollection())).thenReturn(List.of());
            when(marketData.histories(anyCollection(), any(), org.mockito.ArgumentMatchers.anyInt()))
                    .thenReturn(java.util.Map.of());
        }

        ResultActions ask(String question) throws Exception {
            return mvc.perform(post("/api/assistant/ask").header("Authorization", TestJwts.bearer(TestJwts.user(USER)))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"question\":\"" + question + "\"}"));
        }
    }

    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    @ActiveProfiles("test")
    class WithoutProvider extends Base {

        @Test
        void answersDeterministicallyAndSaysSo() throws Exception {
            ask("What is my current portfolio worth?")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.mode").value("DETERMINISTIC"))
                    .andExpect(jsonPath("$.answerKind").value("DETERMINISTIC_TEMPLATE"))
                    .andExpect(jsonPath("$.answer").value(containsString("₹10,00,000.00")))
                    .andExpect(jsonPath("$.notice").value(containsString("No AI provider is configured")))
                    .andExpect(jsonPath("$.facts[0].tool").value("get_portfolio_summary"))
                    .andExpect(jsonPath("$.facts[0].kind").value("CALCULATED"))
                    .andExpect(jsonPath("$.disclaimers[0]").value(containsString("simulated")));
        }

        @Test
        void refusesToTrade() throws Exception {
            ask("Buy 100 RELIANCE for me right now")
                    .andExpect(jsonPath("$.mode").value("REFUSED"))
                    .andExpect(jsonPath("$.facts.length()").value(0));
            verifyNoInteractions(orders);
        }

        @Test
        void otherUsersOrdersAreNotFound() throws Exception {
            UUID foreign = UUID.randomUUID();
            when(orders.order(foreign)).thenThrow(ApiException.notFound("Order " + foreign));
            ask("What is the status of order " + foreign + "?")
                    .andExpect(jsonPath("$.facts[0].kind").value("ERROR"))
                    .andExpect(jsonPath("$.answer").value(containsString("couldn't retrieve")));
        }

        @Test
        void validatesAndAuthenticates() throws Exception {
            ask("x".repeat(501)).andExpect(status().isBadRequest());
            mvc.perform(post("/api/assistant/ask").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"question\":\"hi\"}")).andExpect(status().isUnauthorized());
            mvc.perform(get("/api/assistant/capabilities").header("Authorization", TestJwts.bearer(TestJwts.user(USER))))
                    .andExpect(jsonPath("$.llmEnabled").value(false))
                    .andExpect(jsonPath("$.readOnly").value(true));
        }
    }

    /** Provider configured but unreachable: the real SDK fails and the answer still arrives. */
    @Nested
    @SpringBootTest(properties = {
            "tradeflow.ai.provider=anthropic",
            "tradeflow.ai.api-key=test-key-not-real",
            "tradeflow.ai.base-url=http://localhost:9",
            "tradeflow.ai.request-timeout=PT2S"})
    @AutoConfigureMockMvc
    @ActiveProfiles("test")
    class WithUnreachableProvider extends Base {

        @Test
        void fallsBackToDeterministicAnswer() throws Exception {
            ask("What are my realized and unrealized gains?")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.mode").value("DETERMINISTIC"))
                    .andExpect(jsonPath("$.notice").value(containsString("could not answer")))
                    .andExpect(jsonPath("$.answer").value(containsString("Realized P&L")));
        }
    }
}
