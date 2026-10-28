package com.luffy.tradeflow.common.loadbalancer;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.cloud.loadbalancer.annotation.LoadBalancerClients;
import org.springframework.context.annotation.Configuration;

/** Registers {@link ReadinessCheckedInstances} as the default for all load-balanced clients. */
@Configuration
@ConditionalOnClass(name = "org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier")
@LoadBalancerClients(defaultConfiguration = ReadinessCheckedInstances.class)
public class ReadinessCheckedLoadBalancing {
}
