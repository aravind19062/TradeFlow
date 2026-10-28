package com.luffy.tradeflow_api_gateway.lb;

import org.springframework.cloud.loadbalancer.annotation.LoadBalancerClients;
import org.springframework.context.annotation.Configuration;

/** The gateway routes only to instances that are registered and pass their readiness check. */
@Configuration
@LoadBalancerClients(defaultConfiguration = ReadinessCheckedInstances.class)
public class ReadinessCheckedLoadBalancing {
}
