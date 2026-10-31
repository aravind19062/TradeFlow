package com.luffy.tradeflow.common.events;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Serializes payloads into {@link EventEnvelope} JSON and back. Decoding is strict:
 * wrong event type, unsupported schema version or a payload that fails bean
 * validation raises {@link InvalidEventException}, which the Kafka error handler
 * sends to the dead-letter topic without retrying.
 *
 * <p>Every envelope is stamped with the producer's {@link TradingEnvironment}, and decoding
 * rejects events from an environment this service does not accept. That is the
 * service-boundary guarantee that a LIVE event never drives the DEMO engine and the reverse.
 */
@Component
public class EventCodec {

    public static final int SCHEMA_VERSION = 1;

    private final JsonMapper jsonMapper;
    private final ObjectReader envelopeReader;
    private final Validator validator;
    private final Clock clock;
    private final String producer;
    private final TradingEnvironment environment;
    private final Set<TradingEnvironment> accepted;

    /** DEMO producer accepting DEMO and PLATFORM events (unit tests and the demo services' defaults). */
    public EventCodec(JsonMapper jsonMapper, Validator validator, Clock clock, String producer) {
        this(jsonMapper, validator, clock, producer, TradingEnvironment.DEMO, null);
    }

    @Autowired
    public EventCodec(JsonMapper jsonMapper, Validator validator, Clock clock,
                      @Value("${spring.application.name}") String producer,
                      @Value("${tradeflow.environment}") TradingEnvironment environment,
                      @Value("${tradeflow.events.accepted-environments:}") Set<TradingEnvironment> accepted) {
        this.jsonMapper = jsonMapper;
        // Read decimals as BigDecimal so money never passes through a double.
        this.envelopeReader = jsonMapper.readerFor(EventEnvelope.class)
                .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        this.validator = validator;
        this.clock = clock;
        this.producer = producer;
        this.environment = environment;
        this.accepted = accepted == null || accepted.isEmpty()
                ? EnumSet.of(environment, TradingEnvironment.PLATFORM)
                : EnumSet.copyOf(accepted);
        if ((environment == TradingEnvironment.DEMO && this.accepted.contains(TradingEnvironment.LIVE))
                || (environment == TradingEnvironment.LIVE && this.accepted.contains(TradingEnvironment.DEMO))) {
            throw new IllegalStateException("Refusing to start: a " + environment + " service must never accept "
                    + "events from the other trading environment (accepted: " + this.accepted + ")");
        }
    }

    public TradingEnvironment environment() {
        return environment;
    }

    public EventEnvelope wrap(String eventType, String aggregateId, Object payload) {
        return new EventEnvelope(UUID.randomUUID(), eventType, SCHEMA_VERSION, clock.instant(),
                producer, aggregateId, jsonMapper.valueToTree(payload), environment.name());
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
        TradingEnvironment from;
        try {
            from = TradingEnvironment.ofEnvelope(envelope.environment());
        } catch (IllegalArgumentException e) {
            throw new InvalidEventException("Unknown environment " + envelope.environment());
        }
        if (!accepted.contains(from)) {
            throw new InvalidEventException("Refusing " + from + " event " + envelope.eventId() + " (" + expectedType
                    + "): this " + environment + " service accepts only " + accepted);
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
