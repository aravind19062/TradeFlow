package com.luffy.tradeflow_portfolio_service;

import com.luffy.tradeflow_portfolio_service.client.OrderServiceClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The real OpenFeign client (with circuit breaker and load balancer) must forward the
 * caller's JWT to the downstream service, and a service call must carry the service token.
 */
@SpringBootTest
@ActiveProfiles("test")
class FeignTokenRelayTest {

    private static final AtomicReference<String> lastAuthorization = new AtomicReference<>();
    private static final HttpServer stub = startStub();

    @Autowired OrderServiceClient orderServiceClient;

    private static HttpServer startStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", exchange -> {
                lastAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                byte[] body = (exchange.getRequestURI().getPath().startsWith("/internal")
                        ? "[]" : "{\"order\":{\"status\":\"FILLED\"}}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterAll
    static void stop() {
        stub.stop(0);
    }

    @DynamicPropertySource
    static void routeToStub(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.discovery.client.simple.instances.tradeflow-order-service[0].uri",
                () -> "http://localhost:" + stub.getAddress().getPort());
    }

    @Test
    void relaysTheCallersJwtThroughTheCircuitBreaker() {
        Jwt jwt = Jwt.withTokenValue("user-token-123").header("alg", "HS256").subject("7")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        try {
            orderServiceClient.order(UUID.randomUUID());
        } finally {
            SecurityContextHolder.clearContext();
        }
        assertThat(lastAuthorization.get()).isEqualTo("Bearer user-token-123");
    }

    @Test
    void serviceCallsUseTheServiceTokenNotARelayedUserToken() {
        orderServiceClient.statuses(List.of(UUID.randomUUID()), "service-token-xyz");
        assertThat(lastAuthorization.get()).isEqualTo("Bearer service-token-xyz");
    }
}
