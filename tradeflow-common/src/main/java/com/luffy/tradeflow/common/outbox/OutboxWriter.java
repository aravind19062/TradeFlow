package com.luffy.tradeflow.common.outbox;

import com.luffy.tradeflow.common.events.EventCodec;
import com.luffy.tradeflow.common.events.EventEnvelope;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Appends events to the outbox. {@link Propagation#MANDATORY} makes it impossible
 * to record an event outside the business transaction that caused it.
 */
@Component
public class OutboxWriter {

    private final OutboxEventRepository repository;
    private final EventCodec codec;

    public OutboxWriter(OutboxEventRepository repository, EventCodec codec) {
        this.repository = repository;
        this.codec = codec;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public EventEnvelope append(String topic, String key, Object payload) {
        EventEnvelope envelope = codec.wrap(topic, key, payload);
        repository.save(new OutboxEvent(envelope.eventId(), topic, key, codec.encode(envelope),
                envelope.occurredAt()));
        return envelope;
    }
}
