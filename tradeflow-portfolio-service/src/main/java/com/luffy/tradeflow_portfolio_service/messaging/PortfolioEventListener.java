package com.luffy.tradeflow_portfolio_service.messaging;

import com.luffy.tradeflow.common.events.EventCodec;
import com.luffy.tradeflow.common.events.EventMdc;
import com.luffy.tradeflow.common.events.Events;
import com.luffy.tradeflow.common.events.Topics;
import com.luffy.tradeflow.common.inbox.IdempotentConsumer;
import com.luffy.tradeflow_portfolio_service.service.AccountingService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Each handler runs in one database transaction together with the idempotency
 * marker and any outbox reply; the Kafka offset is committed only afterwards.
 */
@Component
public class PortfolioEventListener {

    private static final String CONSUMER = "portfolio-service";

    private final EventCodec codec;
    private final IdempotentConsumer idempotentConsumer;
    private final AccountingService accountingService;

    public PortfolioEventListener(EventCodec codec, IdempotentConsumer idempotentConsumer,
                                  AccountingService accountingService) {
        this.codec = codec;
        this.idempotentConsumer = idempotentConsumer;
        this.accountingService = accountingService;
    }

    @KafkaListener(topics = Topics.USERS_REGISTERED)
    @Transactional
    public void onUserRegistered(String message) {
        var event = codec.decode(message, Topics.USERS_REGISTERED, Events.UserRegistered.class);
        EventMdc.run(event.envelope(), () -> {
            if (idempotentConsumer.firstDelivery(event.envelope(), CONSUMER)) {
                accountingService.openAccount(event.payload());
            }
        });
    }

    @KafkaListener(topics = Topics.ORDERS_PLACED)
    @Transactional
    public void onOrderPlaced(String message) {
        var event = codec.decode(message, Topics.ORDERS_PLACED, Events.OrderPlaced.class);
        EventMdc.run(event.envelope(), () -> {
            if (idempotentConsumer.firstDelivery(event.envelope(), CONSUMER)) {
                accountingService.reserve(event.payload());
            }
        });
    }

    @KafkaListener(topics = Topics.TRADES_EXECUTED)
    @Transactional
    public void onTradeExecuted(String message) {
        var event = codec.decode(message, Topics.TRADES_EXECUTED, Events.TradeExecuted.class);
        EventMdc.run(event.envelope(), () -> {
            if (idempotentConsumer.firstDelivery(event.envelope(), CONSUMER)) {
                accountingService.settle(event.payload());
            }
        });
    }

    @KafkaListener(topics = Topics.ORDERS_CANCELLED)
    @Transactional
    public void onOrderCancelled(String message) {
        var event = codec.decode(message, Topics.ORDERS_CANCELLED, Events.OrderCancelled.class);
        EventMdc.run(event.envelope(), () -> {
            if (idempotentConsumer.firstDelivery(event.envelope(), CONSUMER)) {
                accountingService.release(event.payload());
            }
        });
    }
}
