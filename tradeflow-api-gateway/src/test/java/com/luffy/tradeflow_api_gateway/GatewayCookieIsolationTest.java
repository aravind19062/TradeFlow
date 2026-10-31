package com.luffy.tradeflow_api_gateway;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression test for a cross-user session leak: the proxy's HTTP client stored a backend's
 * Set-Cookie and sent it on a later request from a different, anonymous caller. Cookies must
 * pass through to the browser untouched, and only a browser's own cookies may go upstream.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GatewayCookieIsolationTest {

    private static final List<String> COOKIES_SEEN_UPSTREAM = new CopyOnWriteArrayList<>();
    private static final HttpServer USER_SERVICE = start();

    private static HttpServer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                String cookie = exchange.getRequestHeaders().getFirst("Cookie");
                COOKIES_SEEN_UPSTREAM.add(cookie == null ? "<none>" : cookie);
                exchange.getResponseHeaders().add("Set-Cookie", "tf_refresh=victim-session; Path=/api/auth; HttpOnly");
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
        registry.add("spring.cloud.discovery.client.simple.instances.tradeflow-user-service[0].uri",
                () -> "http://127.0.0.1:" + USER_SERVICE.getAddress().getPort());
    }

    @AfterAll
    static void stop() {
        USER_SERVICE.stop(0);
    }

    @Autowired MockMvc mvc;

    @Test
    void aCookieSetForOneCallerIsNeverReplayedForAnother() throws Exception {
        // Victim signs in: the backend's cookie is passed through to the victim's browser.
        mvc.perform(post("/api/auth/login").contentType("application/json").content("{}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("victim-session")));
        // An anonymous caller with no cookie must reach the backend with no cookie.
        mvc.perform(post("/api/auth/refresh")).andExpect(status().isOk());
        assertThat(COOKIES_SEEN_UPSTREAM).containsExactly("<none>", "<none>");
    }
}
