package com.luffy.tradeflow.common.security;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ServiceTokenProviderTest {

    private final List<String> authHeaders = new CopyOnWriteArrayList<>();
    private HttpServer server;

    private LoadBalancerClient userServiceAt(int status, String body) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/auth/service-token", ex -> {
            authHeaders.add(ex.getRequestHeaders().getFirst("Authorization"));
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, out.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();
        LoadBalancerClient lb = mock(LoadBalancerClient.class);
        when(lb.choose("tradeflow-user-service")).thenReturn(new DefaultServiceInstance("u1", "tradeflow-user-service",
                "127.0.0.1", server.getAddress().getPort(), false));
        return lb;
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void authenticatesWithClientCredentialsAndCachesTheToken() throws Exception {
        LoadBalancerClient lb = userServiceAt(200, "{\"accessToken\":\"svc-token\",\"tokenType\":\"Bearer\",\"expiresInSeconds\":300}");
        ServiceTokenProvider provider = new ServiceTokenProvider(lb,
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC), "tradeflow-portfolio-service",
                "a-client-secret-of-sufficient-length");
        assertThat(provider.token()).isEqualTo("svc-token");
        assertThat(provider.token()).isEqualTo("svc-token");
        assertThat(authHeaders).hasSize(1);
        assertThat(new String(Base64.getDecoder().decode(authHeaders.get(0).substring(6)), StandardCharsets.UTF_8))
                .isEqualTo("tradeflow-portfolio-service:a-client-secret-of-sufficient-length");
    }

    @Test
    void failsClearlyWithoutCredentialsOrWhenRefused() throws Exception {
        LoadBalancerClient lb = userServiceAt(401, "{\"code\":\"INVALID_CLIENT\"}");
        assertThatThrownBy(() -> new ServiceTokenProvider(lb, Clock.systemUTC(), "svc", "").token())
                .hasMessageContaining("service-client.secret is not configured");
        assertThatThrownBy(() -> new ServiceTokenProvider(lb, Clock.systemUTC(), "svc", "wrong-secret-0123456789abc").token())
                .isInstanceOf(RuntimeException.class);
    }
}
