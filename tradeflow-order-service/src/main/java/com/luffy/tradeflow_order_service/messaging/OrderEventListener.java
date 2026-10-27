package com.luffy.tradeflow_order_service.messaging;

import com.luffy.tradeflow.common.events.EventCodec;
import com.luffy.tradeflow.common.events.EventMdc;
import com.luffy.tradeflow.common.events.Events;
import com.luffy.tradeflow.common.events.Topics;
import com.luffy.tradeflow.common.inbox.IdempotentConsumer;
import com.luffy.tradeflow_order_service.service.ExecutionService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Each handler runs in one database transaction together with the idempotency
 * marker and any outbox writes; the Kafka offset is committed only afterwards.
 */
@Component
public class OrderEventListener {

    private static final String CONSUMER = "order-service";

    private final EventCodec codec;
    private final IdempotentConsumer idempotentConsumer;
    private final ExecutionService executionService;

    public OrderEventListener(EventCodec codec, IdempotentConsumer idempotentConsumer,
                              ExecutionService executionService) {
        this.codec = codec;
        this.idempotentConsumer = idempotentConsumer;
        this.executionService = executionService;
    }

    @KafkaListener(topics = Topics.ORDERS_RESERVED)
    @Transactional
    public void onReserved(String message) {
        var event = codec.decode(message, Topics.ORDERS_RESERVED, Events.OrderReserved.class);
        EventMdc.run(event.envelope(), () -> {
            if (idempotentConsumer.firstDelivery(event.envelope(), CONSUMER)) {
                executionService.onReserved(event.payload());
            }
        });
    }

    @KafkaListener(topics = Topics.ORDERS_REJECTED)
    @Transactional
    public void onRejected(String message) {
        var event = codec.decode(message, Topics.ORDERS_REJECTED, Events.OrderRejected.class);
        EventMdc.run(event.envelope(), () -> {
            if (idempotentConsumer.firstDelivery(event.envelope(), CONSUMER)) {
                executionService.onRejected(event.payload());
            }
        });
    }

    @KafkaListener(topics = Topics.PRICES_UPDATED)
    @Transactional
    public void onPriceUpdated(String message) {
        var event = codec.decode(message, Topics.PRICES_UPDATED, Events.PriceUpdated.class);
        EventMdc.run(event.envelope(), () -> {
            if (idempotentConsumer.firstDelivery(event.envelope(), CONSUMER)) {
                executionService.onPriceUpdated(event.payload());
            }
        });
    }
}
