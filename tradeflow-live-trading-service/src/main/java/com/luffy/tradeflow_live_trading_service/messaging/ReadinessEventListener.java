package com.luffy.tradeflow_live_trading_service.messaging;

import com.luffy.tradeflow.common.events.EventCodec;
import com.luffy.tradeflow.common.events.EventMdc;
import com.luffy.tradeflow.common.events.Events;
import com.luffy.tradeflow.common.events.Topics;
import com.luffy.tradeflow.common.inbox.IdempotentConsumer;
import com.luffy.tradeflow_live_trading_service.audit.LiveAuditService;
import com.luffy.tradeflow_live_trading_service.notifications.NotificationService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Readiness changes (PLATFORM events from learning-service) become user notifications. The event
 * never grants or removes access by itself: the guard asks learning-service synchronously on every
 * action. Per D5, losing readiness blocks new orders only; nothing here cancels a broker order.
 */
@Component
public class ReadinessEventListener {

    private static final String CONSUMER = "live-trading-service";

    private final EventCodec codec;
    private final IdempotentConsumer idempotent;
    private final NotificationService notifications;
    private final LiveAuditService audit;

    public ReadinessEventListener(EventCodec codec, IdempotentConsumer idempotent, NotificationService notifications,
                                  LiveAuditService audit) {
        this.codec = codec;
        this.idempotent = idempotent;
        this.notifications = notifications;
        this.audit = audit;
    }

    @KafkaListener(topics = Topics.READINESS_CHANGED)
    @Transactional
    public void onReadinessChanged(String message) {
        var event = codec.decode(message, Topics.READINESS_CHANGED, Events.ReadinessChanged.class);
        EventMdc.run(event.envelope(), () -> {
            if (idempotent.firstDelivery(event.envelope(), CONSUMER)) {
                handle(event.payload());
            }
        });
    }

    void handle(Events.ReadinessChanged e) {
        boolean wasQualified = "QUALIFIED".equals(e.fromState());
        if (wasQualified && !e.qualified()) {
            notifications.notify(e.userId(), "READINESS_LOST", "Your live-trading access has ended (" + e.reasonCode()
                    + "). New live orders are blocked. Orders already at your broker were NOT cancelled: you can still "
                    + "view and cancel them here or at your broker. Requalify in the Academy to trade again.");
            audit.record(e.userId(), "READINESS_LOST", LiveAuditService.Outcome.INFO, e.reasonCode(), 0);
        } else if (!wasQualified && e.qualified()) {
            notifications.notify(e.userId(), "READINESS_GRANTED", "You completed the readiness requirements (policy v"
                    + e.policyVersion() + "). This is not financial advice or a judgement of suitability; real "
                    + "trading can lose money.");
            audit.record(e.userId(), "READINESS_GRANTED", LiveAuditService.Outcome.INFO, "policy v" + e.policyVersion(), 0);
        }
    }
}
