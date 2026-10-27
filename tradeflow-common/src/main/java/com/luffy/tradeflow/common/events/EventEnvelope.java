package com.luffy.tradeflow.common.events;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * Wire format shared by every TradeFlow event.
 *
 * @param eventId       stable unique id; consumers use it for de-duplication
 * @param eventType     equals the topic name, e.g. {@code trades.executed}
 * @param schemaVersion payload schema version; bumped on breaking changes
 * @param occurredAt    when the producing transaction created the event
 * @param producer      spring.application.name of the producer
 * @param aggregateId   id of the entity the event is about (also the Kafka key)
 * @param payload       event-specific body, see the records in this package
 */
public record EventEnvelope(
        UUID eventId,
        String eventType,
        int schemaVersion,
        Instant occurredAt,
        String producer,
        String aggregateId,
        JsonNode payload) {
}
