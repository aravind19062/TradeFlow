package com.luffy.tradeflow.common.outbox;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;

/**
 * Reports the outbox backlog. DOWN when the oldest unpublished event is older
 * than {@code tradeflow.outbox.max-lag}, which means events are not reaching Kafka.
 */
@Component("outbox")
public class OutboxHealthIndicator extends AbstractHealthIndicator {

    private final OutboxEventRepository repository;
    private final Clock clock;
    private final Duration maxLag;

    public OutboxHealthIndicator(OutboxEventRepository repository, Clock clock,
                                 @Value("${tradeflow.outbox.max-lag:PT1M}") Duration maxLag) {
        this.repository = repository;
        this.clock = clock;
        this.maxLag = maxLag;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        long pending = repository.countByPublishedAtIsNull();
        builder.withDetail("pendingEvents", pending);
        repository.findFirstByPublishedAtIsNullOrderByIdAsc().ifPresentOrElse(oldest -> {
            Duration lag = Duration.between(oldest.getCreatedAt(), clock.instant());
            builder.withDetail("oldestPendingAgeSeconds", lag.toSeconds());
            if (oldest.getLastError() != null) {
                builder.withDetail("lastError", oldest.getLastError());
            }
            if (lag.compareTo(maxLag) > 0) {
                builder.down();
            } else {
                builder.up();
            }
        }, builder::up);
    }
}
