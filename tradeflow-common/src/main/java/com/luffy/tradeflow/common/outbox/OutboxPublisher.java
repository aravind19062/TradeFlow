package com.luffy.tradeflow.common.outbox;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Polls the outbox and publishes pending events to Kafka in insertion order.
 *
 * <p>Delivery is at-least-once: if the process dies after Kafka acknowledges a
 * send but before {@code published_at} is saved, the event is sent again, so
 * every consumer de-duplicates by {@code eventId}. On the first failure the batch
 * stops (preserving order) and polling backs off, so a Kafka outage produces a
 * growing backlog that is visible in {@code /actuator/health}, never silent loss.
 *
 * <p>Assumes one publisher instance per service. Running several replicas would
 * need row locking (e.g. {@code FOR UPDATE SKIP LOCKED}) or leader election.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "tradeflow.outbox.publisher-enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPublisher {

    private final OutboxEventRepository repository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final Clock clock;
    private final Duration sendTimeout;
    private final Duration failureBackoff;
    private volatile Instant pausedUntil = Instant.MIN;
    private volatile boolean failing;

    public OutboxPublisher(OutboxEventRepository repository, KafkaTemplate<String, String> kafkaTemplate, Clock clock,
                           @Value("${tradeflow.outbox.send-timeout:PT10S}") Duration sendTimeout,
                           @Value("${tradeflow.outbox.failure-backoff:PT5S}") Duration failureBackoff) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
        this.clock = clock;
        this.sendTimeout = sendTimeout;
        this.failureBackoff = failureBackoff;
    }

    @Scheduled(fixedDelayString = "${tradeflow.outbox.poll-interval-ms:500}")
    public void publishPending() {
        if (clock.instant().isBefore(pausedUntil)) {
            return;
        }
        List<OutboxEvent> batch = repository.findTop100ByPublishedAtIsNullOrderByIdAsc();
        for (OutboxEvent event : batch) {
            try {
                kafkaTemplate.send(event.getTopic(), event.getMessageKey(), event.getPayload())
                        .get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
                event.markPublished(clock.instant());
                repository.save(event);
                if (failing) {
                    log.info("Kafka publishing recovered; outbox draining again");
                    failing = false;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                event.markFailed(rootMessage(e));
                repository.save(event);
                pausedUntil = clock.instant().plus(failureBackoff);
                if (!failing) {
                    log.error("Cannot publish outbox event {} to topic '{}': {}. Events are kept in the outbox and "
                                    + "will be retried. Is Kafka running at the configured bootstrap servers?",
                            event.getEventId(), event.getTopic(), rootMessage(e));
                    failing = true;
                }
                return;
            }
        }
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t.getClass().getSimpleName() + ": " + t.getMessage();
    }
}
