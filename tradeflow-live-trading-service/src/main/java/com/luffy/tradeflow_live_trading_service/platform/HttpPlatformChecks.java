package com.luffy.tradeflow_live_trading_service.platform;

import com.luffy.tradeflow.common.security.ServiceTokenProvider;
import com.luffy.tradeflow.common.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Calls the /internal endpoints of learning-service and user-service with this service's own
 * SERVICE token (it cannot mint tokens; it holds only a client secret). No result is cached: every
 * live action sees the current answer. Plain JDK HTTP client, so no cookies are ever stored.
 */
@Component
public class HttpPlatformChecks implements PlatformChecks {

    private static final Logger log = LoggerFactory.getLogger(HttpPlatformChecks.class);
    private static final String LEARNING = "tradeflow-learning-service";
    private static final String USERS = "tradeflow-user-service";

    private final LoadBalancerClient loadBalancer;
    private final ServiceTokenProvider tokens;
    private final RestClient http;

    public HttpPlatformChecks(LoadBalancerClient loadBalancer, ServiceTokenProvider tokens) {
        this.loadBalancer = loadBalancer;
        this.tokens = tokens;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(3));
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public Eligibility eligibility(long userId) {
        return get(LEARNING, "/internal/readiness/" + userId + "/eligibility", Eligibility.class);
    }

    @Override
    public SessionState session(long userId) {
        return get(USERS, "/internal/users/" + userId + "/session-state", SessionState.class);
    }

    private <T> T get(String service, String path, Class<T> type) {
        try {
            ServiceInstance instance = loadBalancer.choose(service);
            if (instance == null) {
                throw new IllegalStateException("no instance");
            }
            T body = http.get().uri(instance.getUri().resolve(path))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.token())
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve().body(type);
            if (body == null) {
                throw new IllegalStateException("empty response");
            }
            return body;
        } catch (RuntimeException e) {
            log.warn("Live safety check against {} failed ({}); refusing the action", service, e.getClass().getSimpleName());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "LIVE_CHECK_UNAVAILABLE",
                    "A required safety check could not be completed, so the action was refused. Try again shortly.");
        }
    }
}
