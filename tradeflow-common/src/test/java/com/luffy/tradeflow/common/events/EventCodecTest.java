package com.luffy.tradeflow.common.events;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventCodecTest {

    private static final ValidatorFactory VALIDATION = Validation.buildDefaultValidatorFactory();
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final EventCodec codec = new EventCodec(mapper, VALIDATION.getValidator(),
            Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC), "test-producer");

    @AfterAll
    static void close() {
        VALIDATION.close();
    }

    private Events.TradeExecuted trade(BigDecimal price, BigDecimal gross) {
        return new Events.TradeExecuted(UUID.randomUUID(), UUID.randomUUID(), 7L, "TCS", Events.OrderSide.BUY, 3,
                price, gross, Instant.parse("2026-01-01T00:00:00Z"));
    }

    @Test
    void roundTripPreservesEnvelopeAndExactDecimals() {
        var payload = trade(new BigDecimal("1234.57"), new BigDecimal("3703.71"));
        EventEnvelope envelope = codec.wrap(Topics.TRADES_EXECUTED, "7", payload);

        var decoded = codec.decode(codec.encode(envelope), Topics.TRADES_EXECUTED, Events.TradeExecuted.class);

        assertThat(decoded.envelope().eventId()).isEqualTo(envelope.eventId());
        assertThat(decoded.envelope().schemaVersion()).isEqualTo(1);
        assertThat(decoded.envelope().producer()).isEqualTo("test-producer");
        assertThat(decoded.payload()).isEqualTo(payload);
        assertThat(decoded.payload().grossAmount()).isEqualByComparingTo("3703.71");
    }

    @Test
    void rejectsWrongEventType() {
        String json = codec.encode(codec.wrap(Topics.TRADES_EXECUTED, "7",
                trade(BigDecimal.TEN, BigDecimal.TEN)));
        assertThatThrownBy(() -> codec.decode(json, Topics.ORDERS_PLACED, Events.OrderPlaced.class))
                .isInstanceOf(InvalidEventException.class).hasMessageContaining("Expected event type");
    }

    @Test
    void rejectsUnsupportedSchemaVersion() {
        String json = codec.encode(codec.wrap(Topics.TRADES_EXECUTED, "7", trade(BigDecimal.TEN, BigDecimal.TEN)))
                .replace("\"schemaVersion\":1", "\"schemaVersion\":2");
        assertThatThrownBy(() -> codec.decode(json, Topics.TRADES_EXECUTED, Events.TradeExecuted.class))
                .isInstanceOf(InvalidEventException.class).hasMessageContaining("Unsupported schema version 2");
    }

    @Test
    void rejectsPayloadThatFailsValidation() {
        String json = codec.encode(codec.wrap(Topics.TRADES_EXECUTED, "7",
                trade(new BigDecimal("-1"), BigDecimal.TEN)));
        assertThatThrownBy(() -> codec.decode(json, Topics.TRADES_EXECUTED, Events.TradeExecuted.class))
                .isInstanceOf(InvalidEventException.class).hasMessageContaining("price");
    }

    @Test
    void rejectsMalformedJson() {
        assertThatThrownBy(() -> codec.decode("{not json", Topics.TRADES_EXECUTED, Events.TradeExecuted.class))
                .isInstanceOf(InvalidEventException.class).hasMessageContaining("Malformed");
    }
}
