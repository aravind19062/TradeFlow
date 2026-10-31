package com.luffy.tradeflow_api_gateway;

import com.luffy.tradeflow.common.testsupport.TestJwts;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DEMO routes: /api/demo/** reaches the simulated services on their existing paths, legacy paths
 * keep working, and every demo response is labelled X-TradeFlow-Environment: DEMO. A stub HTTP
 * server stands in for all three demo services and records the paths it receives.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GatewayEnvironmentRoutingTest {

    private static final List<String> RECEIVED = new CopyOnWriteArrayList<>();
    private static final HttpServer STUB = start();

    private static HttpServer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                RECEIVED.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
                byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void instances(DynamicPropertyRegistry registry) {
        String uri = "http://127.0.0.1:" + STUB.getAddress().getPort();
        for (String service : List.of("tradeflow-market-data-service", "tradeflow-order-service",
                "tradeflow-portfolio-service", "tradeflow-live-trading-service")) {
            registry.add("spring.cloud.discovery.client.simple.instances." + service + "[0].uri", () -> uri);
        }
    }

    @AfterAll
    static void stop() {
        STUB.stop(0);
    }

    @Autowired MockMvc mvc;

    @BeforeEach
    void clear() {
        RECEIVED.clear();
    }

    @Test
    void demoPathsAreRewrittenToTheSimulatedServicesAndLabelledDemo() throws Exception {
        String user = TestJwts.bearer(TestJwts.user(5));
        mvc.perform(get("/api/demo/market/quotes?symbols=TCS"))
                .andExpect(status().isOk()).andExpect(header().string("X-TradeFlow-Environment", "DEMO"));
        mvc.perform(get("/api/demo/orders").header("Authorization", user))
                .andExpect(status().isOk()).andExpect(header().string("X-TradeFlow-Environment", "DEMO"));
        mvc.perform(post("/api/demo/assistant/ask").header("Authorization", user).contentType("application/json")
                        .content("{\"question\":\"q\"}"))
                .andExpect(status().isOk()).andExpect(header().string("X-TradeFlow-Environment", "DEMO"));
        mvc.perform(get("/api/demo/portfolio/summary").header("Authorization", user))
                .andExpect(status().isOk());

        assertThat(RECEIVED).containsExactly("GET /api/market/quotes?symbols=TCS", "GET /api/orders",
                "POST /api/assistant/ask", "GET /api/portfolio/summary");
    }

    @Test
    void legacyDemoPathsStillWorkAndAreLabelledDemo() throws Exception {
        mvc.perform(get("/api/market/instruments"))
                .andExpect(status().isOk()).andExpect(header().string("X-TradeFlow-Environment", "DEMO"));
        mvc.perform(get("/api/trades").header("Authorization", TestJwts.bearer(TestJwts.user(5))))
                .andExpect(status().isOk()).andExpect(header().string("X-TradeFlow-Environment", "DEMO"));
        assertThat(RECEIVED).containsExactly("GET /api/market/instruments", "GET /api/trades");
    }

    @Test
    void demoRoutesKeepTheSameEdgeRules() throws Exception {
        mvc.perform(get("/api/demo/orders")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/demo/market/simulation/advance")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/demo/internal/orders")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/demo/internal/orders").header("Authorization", TestJwts.bearer(TestJwts.user(5))))
                .andExpect(status().is4xxClientError()); // not a demo route: nothing is proxied
        assertThat(RECEIVED).isEmpty();
    }

    @Test
    void livePathsReachOnlyTheLiveServiceAndAreLabelledLive() throws Exception {
        String user = TestJwts.bearer(TestJwts.user(5));
        mvc.perform(get("/api/live/status").header("Authorization", user))
                .andExpect(status().isOk()).andExpect(header().string("X-TradeFlow-Environment", "LIVE"));
        mvc.perform(get("/api/admin/live/kill-switch").header("Authorization", TestJwts.bearer(TestJwts.admin(1))))
                .andExpect(status().isOk()).andExpect(header().string("X-TradeFlow-Environment", "LIVE"));
        // No demo path leads to live trading.
        mvc.perform(get("/api/demo/live/status").header("Authorization", user)).andExpect(status().is4xxClientError());
        assertThat(RECEIVED).containsExactly("GET /api/live/status", "GET /api/admin/live/kill-switch");
    }
}
