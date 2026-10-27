package com.luffy.tradeflow.common.events;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Serializes payloads into {@link EventEnvelope} JSON and back. Decoding is strict:
 * wrong event type, unsupported schema version or a payload that fails bean
 * validation raises {@link InvalidEventException}, which the Kafka error handler
 * sends to the dead-letter topic without retrying.
 */
@Component
public class EventCodec {

    public static final int SCHEMA_VERSION = 1;

    private final JsonMapper jsonMapper;
    private final ObjectReader envelopeReader;
    private final Validator validator;
    private final Clock clock;
    private final String producer;

    public EventCodec(JsonMapper jsonMapper, Validator validator, Clock clock,
                      @Value("${spring.application.name}") String producer) {
        this.jsonMapper = jsonMapper;
        // Read decimals as BigDecimal so money never passes through a double.
        this.envelopeReader = jsonMapper.readerFor(EventEnvelope.class)
                .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        this.validator = validator;
        this.clock = clock;
        this.producer = producer;
    }

    public EventEnvelope wrap(String eventType, String aggregateId, Object payload) {
        return new EventEnvelope(UUID.randomUUID(), eventType, SCHEMA_VERSION, clock.instant(),
                producer, aggregateId, jsonMapper.valueToTree(payload));
    }

    public String encode(EventEnvelope envelope) {
        return jsonMapper.writeValueAsString(envelope);
    }

    public <T> Decoded<T> decode(String json, String expectedType, Class<T> payloadType) {
        EventEnvelope envelope;
        T payload;
        try {
            envelope = envelopeReader.readValue(json);
        } catch (JacksonException e) {
            throw new InvalidEventException("Malformed event JSON: " + e.getOriginalMessage(), e);
        }
        if (envelope.eventId() == null || envelope.payload() == null) {
            throw new InvalidEventException("Event is missing eventId or payload");
        }
        if (!expectedType.equals(envelope.eventType())) {
            throw new InvalidEventException("Expected event type " + expectedType + " but got " + envelope.eventType());
        }
        if (envelope.schemaVersion() != SCHEMA_VERSION) {
            throw new InvalidEventException("Unsupported schema version " + envelope.schemaVersion()
                    + " for " + expectedType);
        }
        try {
            payload = jsonMapper.treeToValue(envelope.payload(), payloadType);
        } catch (JacksonException e) {
            throw new InvalidEventException("Payload does not match " + payloadType.getSimpleName()
                    + ": " + e.getOriginalMessage(), e);
        }
        Set<ConstraintViolation<T>> violations = validator.validate(payload);
        if (!violations.isEmpty()) {
            String detail = violations.stream()
                    .map(v -> v.getPropertyPath() + " " + v.getMessage())
                    .sorted()
                    .collect(Collectors.joining(", "));
            throw new InvalidEventException("Invalid " + expectedType + " payload: " + detail);
        }
        return new Decoded<>(envelope, payload);
    }

    public record Decoded<T>(EventEnvelope envelope, T payload) {
    }
}
