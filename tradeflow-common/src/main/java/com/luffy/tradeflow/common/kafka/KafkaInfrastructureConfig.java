package com.luffy.tradeflow.common.kafka;

import com.luffy.tradeflow.common.events.InvalidEventException;
import com.luffy.tradeflow.common.events.Topics;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import java.util.ArrayList;
import java.util.List;

/**
 * Topics and consumer error handling shared by all services.
 *
 * <p>Transient failures (database briefly unavailable, optimistic-lock conflict)
 * are retried with a fixed back-off; after the last attempt, or immediately for
 * {@link InvalidEventException}/{@link InconsistentStateException}, the record is
 * published to {@code <topic>.DLT} with the exception in its headers.
 */
@Slf4j
@Configuration
public class KafkaInfrastructureConfig {

    @Bean
    public KafkaAdmin.NewTopics tradeflowTopics(@Value("${tradeflow.kafka.partitions:3}") int partitions,
                                                @Value("${tradeflow.kafka.replication-factor:1}") int replicas) {
        List<NewTopic> topics = new ArrayList<>();
        for (String topic : Topics.ALL) {
            topics.add(TopicBuilder.name(topic).partitions(partitions).replicas(replicas).build());
            // The recoverer publishes to the same partition number, so DLTs need as many partitions.
            topics.add(TopicBuilder.name(topic + Topics.DLT_SUFFIX).partitions(partitions).replicas(replicas).build());
        }
        return new KafkaAdmin.NewTopics(topics.toArray(NewTopic[]::new));
    }

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate,
                                                 @Value("${tradeflow.kafka.retry.interval-ms:1000}") long intervalMs,
                                                 @Value("${tradeflow.kafka.retry.max-attempts:3}") long maxRetries) {
        // Explicit destination: <topic>.DLT, same partition (DLT topics are created with as many partitions).
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, exception) -> new TopicPartition(record.topic() + Topics.DLT_SUFFIX, record.partition()));
        DefaultErrorHandler handler = new DefaultErrorHandler((record, exception) -> {
            log.error("Sending record from {}-{}@{} to DLT: {}", record.topic(), record.partition(), record.offset(),
                    exception.getCause() != null ? exception.getCause().getMessage() : exception.getMessage());
            recoverer.accept(record, exception);
        }, new FixedBackOff(intervalMs, maxRetries));
        handler.addNotRetryableExceptions(InvalidEventException.class, InconsistentStateException.class);
        return handler;
    }
}
