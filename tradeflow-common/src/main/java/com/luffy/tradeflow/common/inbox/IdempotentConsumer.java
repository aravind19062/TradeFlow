package com.luffy.tradeflow.common.inbox;

import com.luffy.tradeflow.common.events.EventEnvelope;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * Records event ids inside the consumer's database transaction. The handler's
 * side effects and the "processed" marker commit or roll back together, so a
 * redelivered event is skipped and a failed one is retried in full.
 *
 * <p>Two concurrent deliveries of the same event both pass the existence check,
 * but the primary key lets only one commit; the loser rolls back and, on retry,
 * sees the event as already processed.
 */
@Slf4j
@Component
public class IdempotentConsumer {

    private final ProcessedEventRepository repository;
    private final Clock clock;

    public IdempotentConsumer(ProcessedEventRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /** @return true if this is the first time the event is seen and the handler should run */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean firstDelivery(EventEnvelope envelope, String consumer) {
        if (repository.existsById(envelope.eventId())) {
            log.info("Skipping duplicate {} event {}", envelope.eventType(), envelope.eventId());
            return false;
        }
        repository.saveAndFlush(new ProcessedEvent(envelope.eventId(), consumer, clock.instant()));
        return true;
    }
}
