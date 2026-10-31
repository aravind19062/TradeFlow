package com.luffy.tradeflow_learning_service.messaging;

import com.luffy.tradeflow.common.events.EventCodec;
import com.luffy.tradeflow.common.events.EventMdc;
import com.luffy.tradeflow.common.events.Events;
import com.luffy.tradeflow.common.events.Topics;
import com.luffy.tradeflow.common.inbox.IdempotentConsumer;
import com.luffy.tradeflow_learning_service.readiness.ExerciseService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads DEMO order events as evidence for practical exercises (read-only: nothing here can affect
 * an order). The codec accepts only DEMO and PLATFORM events; a LIVE event is refused to the DLT.
 */
@Component
public class DemoOrderEventListener {

    private static final String CONSUMER = "learning-service";

    private final EventCodec codec;
    private final IdempotentConsumer idempotent;
    private final ExerciseService exercises;

    public DemoOrderEventListener(EventCodec codec, IdempotentConsumer idempotent, ExerciseService exercises) {
        this.codec = codec;
        this.idempotent = idempotent;
        this.exercises = exercises;
    }

    @KafkaListener(topics = Topics.ORDERS_PLACED)
    @Transactional
    public void onPlaced(String message) {
        var event = codec.decode(message, Topics.ORDERS_PLACED, Events.OrderPlaced.class);
        EventMdc.run(event.envelope(), () -> {
            if (idempotent.firstDelivery(event.envelope(), CONSUMER)) {
                exercises.onPlaced(event.payload(), event.envelope().occurredAt());
            }
        });
    }

    @KafkaListener(topics = Topics.ORDERS_RESERVED)
    @Transactional
    public void onReserved(String message) {
        var event = codec.decode(message, Topics.ORDERS_RESERVED, Events.OrderReserved.class);
        EventMdc.run(event.envelope(), () -> {
            if (idempotent.firstDelivery(event.envelope(), CONSUMER)) {
                exercises.onReserved(event.payload(), event.envelope().occurredAt());
            }
        });
    }

    @KafkaListener(topics = Topics.ORDERS_CANCELLED)
    @Transactional
    public void onCancelled(String message) {
        var event = codec.decode(message, Topics.ORDERS_CANCELLED, Events.OrderCancelled.class);
        EventMdc.run(event.envelope(), () -> {
            if (idempotent.firstDelivery(event.envelope(), CONSUMER)) {
                exercises.onCancelled(event.payload(), event.envelope().occurredAt());
            }
        });
    }

    @KafkaListener(topics = Topics.TRADES_EXECUTED)
    @Transactional
    public void onExecuted(String message) {
        var event = codec.decode(message, Topics.TRADES_EXECUTED, Events.TradeExecuted.class);
        EventMdc.run(event.envelope(), () -> {
            if (idempotent.firstDelivery(event.envelope(), CONSUMER)) {
                exercises.onExecuted(event.payload(), event.envelope().occurredAt());
            }
        });
    }
}
