package com.luffy.tradeflow.common.kafka;

import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.DescribeClusterOptions;
import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Checks that the Kafka cluster answers a metadata request within a few seconds. */
@Component("kafka")
public class KafkaHealthIndicator extends AbstractHealthIndicator {

    private static final int TIMEOUT_MS = 3000;

    private final AdminClient adminClient;
    private final Object bootstrapServers;

    public KafkaHealthIndicator(KafkaAdmin kafkaAdmin) {
        Map<String, Object> config = new HashMap<>(kafkaAdmin.getConfigurationProperties());
        config.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, TIMEOUT_MS);
        config.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, TIMEOUT_MS);
        this.bootstrapServers = config.get(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG);
        this.adminClient = AdminClient.create(config);
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) throws Exception {
        builder.withDetail("bootstrapServers", String.valueOf(bootstrapServers));
        String clusterId = adminClient.describeCluster(new DescribeClusterOptions().timeoutMs(TIMEOUT_MS))
                .clusterId().get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        builder.up().withDetail("clusterId", clusterId);
    }

    @PreDestroy
    void close() {
        adminClient.close();
    }
}
