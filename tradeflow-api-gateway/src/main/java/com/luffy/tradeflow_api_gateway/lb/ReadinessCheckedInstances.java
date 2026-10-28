package com.luffy.tradeflow_api_gateway.lb;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Load-balancer instance list = instances registered in Eureka AND currently passing
 * their readiness check (spring.cloud.loadbalancer.health-check.path, interval). The
 * checks use a dedicated plain RestClient (never a load-balanced one, which would try
 * to resolve the instance IP as a service name). The instance list is re-fetched, so
 * new replicas join and removed ones leave without restarts.
 *
 * <p>Applied to every load-balanced client via {@link ReadinessCheckedLoadBalancing}.
 * Disabled with tradeflow.loadbalancer.readiness-checks=false (tests, no-Docker mode).
 */
public class ReadinessCheckedInstances {

    @Bean
    @ConditionalOnProperty(name = "tradeflow.loadbalancer.readiness-checks", havingValue = "true", matchIfMissing = true)
    public ServiceInstanceListSupplier readinessCheckedInstanceSupplier(ConfigurableApplicationContext context) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(1));
        factory.setReadTimeout(Duration.ofSeconds(2));
        RestClient plain = RestClient.builder().requestFactory(factory).build();
        return ServiceInstanceListSupplier.builder()
                .withBlockingDiscoveryClient()
                .withBlockingHealthChecks(plain)
                .build(context);
    }
}
