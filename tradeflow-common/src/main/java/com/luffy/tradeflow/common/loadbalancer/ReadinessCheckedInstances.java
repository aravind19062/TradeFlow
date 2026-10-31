package com.luffy.tradeflow.common.loadbalancer;

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
        // A single slow answer removes the instance until the next check (interval), and with one
        // replica that means "no servers" (503). Right after a cold start, eleven JVMs warming up on a
        // 4-CPU laptop VM can take more than 2 s to answer, so allow 5 s by default.
        factory.setReadTimeout(context.getEnvironment().getProperty(
                "tradeflow.loadbalancer.health-check-timeout", Duration.class, Duration.ofSeconds(5)));
        RestClient plain = RestClient.builder().requestFactory(factory).build();
        return ServiceInstanceListSupplier.builder()
                .withBlockingDiscoveryClient()
                .withBlockingHealthChecks(plain)
                .build(context);
    }
}
