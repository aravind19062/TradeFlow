package com.luffy.tradeflow.common.security;

import com.luffy.tradeflow.common.web.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/**
 * Obtains short-lived SERVICE-role tokens for background service-to-service calls that have no
 * user token to relay (e.g. reconciliation jobs). Services cannot sign tokens: they authenticate
 * to user-service with their own client credentials (client id = spring.application.name) and
 * receive a token for that identity only. Endpoints under /internal/** accept only this role;
 * the API gateway never routes /internal/**.
 */
@Component
public class ServiceTokenProvider {

    public static final String ROLE_SERVICE = "SERVICE";
    private static final String USER_SERVICE = "tradeflow-user-service";

    record TokenResponse(String accessToken, String tokenType, long expiresInSeconds) {
    }

    private final LoadBalancerClient loadBalancer;
    private final Clock clock;
    private final String clientId;
    private final String clientSecret;
    private final RestClient http;
    private volatile String cached;
    private volatile Instant refreshAfter = Instant.MIN;

    public ServiceTokenProvider(LoadBalancerClient loadBalancer, Clock clock,
                                @Value("${spring.application.name}") String clientId,
                                @Value("${tradeflow.security.service-client.secret:}") String clientSecret) {
        this.loadBalancer = loadBalancer;
        this.clock = clock;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    public String token() {
        Instant now = clock.instant();
        if (cached == null || now.isAfter(refreshAfter)) {
            synchronized (this) {
                if (cached == null || clock.instant().isAfter(refreshAfter)) {
                    fetch();
                }
            }
        }
        return cached;
    }

    private void fetch() {
        if (clientSecret == null || clientSecret.isBlank()) {
            throw new IllegalStateException("tradeflow.security.service-client.secret is not configured for "
                    + clientId + "; it cannot call other services' internal endpoints");
        }
        ServiceInstance instance = loadBalancer.choose(USER_SERVICE);
        if (instance == null) {
            throw ApiException.dependencyUnavailable(USER_SERVICE, "no instance available to issue a service token");
        }
        String basic = Base64.getEncoder().encodeToString((clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
        TokenResponse response = http.post()
                .uri(instance.getUri().resolve("/internal/auth/service-token"))
                .header(HttpHeaders.AUTHORIZATION, "Basic " + basic)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(TokenResponse.class);
        if (response == null || response.accessToken() == null) {
            throw ApiException.dependencyUnavailable(USER_SERVICE, "empty service-token response");
        }
        cached = response.accessToken();
        refreshAfter = clock.instant().plusSeconds(Math.max(0, response.expiresInSeconds() - 60));
    }
}
