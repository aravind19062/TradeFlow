package com.luffy.tradeflow_user_service.config;

import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/** Adds the shared outbox/inbox entities from tradeflow-common to this service's JPA setup. */
@Configuration
@EntityScan(basePackages = {"com.luffy.tradeflow_user_service", "com.luffy.tradeflow.common"})
@EnableJpaRepositories(basePackages = {"com.luffy.tradeflow_user_service", "com.luffy.tradeflow.common"})
public class PersistenceConfig {
}
