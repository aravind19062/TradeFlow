package com.luffy.tradeflow_order_service.saga;

import com.luffy.tradeflow.common.events.Events;
import com.luffy.tradeflow.common.events.Topics;
import com.luffy.tradeflow.common.outbox.OutboxWriter;
import com.luffy.tradeflow_order_service.domain.Order;
import com.luffy.tradeflow_order_service.domain.Trade;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * All saga transitions. Every method joins the caller's transaction, so the saga
 * record, the order and any outbox event (compensation or re-drive) commit together.
 */
@Slf4j
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class OrderSagaService {

    private final OrderSagaRepository repository;
    private final OutboxWriter outboxWriter;
    private final SagaProperties properties;
    private final Clock clock;

    public OrderSagaService(OrderSagaRepository repository, OutboxWriter outboxWriter, SagaProperties properties,
                            Clock clock) {
        this.repository = repository;
        this.outboxWriter = outboxWriter;
        this.properties = properties;
        this.clock = clock;
    }

    public void start(Order order) {
        Instant now = clock.instant();
        repository.save(new OrderSaga(order.getId(), now.plus(properties.reservationTimeout()), now));
    }

    public void reservedAndFilled(UUID orderId) {
        Instant now = clock.instant();
        saga(orderId, now).moveTo(SagaState.AWAITING_SETTLEMENT, now.plus(properties.settlementTimeout()), now);
    }

    public void reservedAndResting(UUID orderId) {
        Instant now = clock.instant();
        saga(orderId, now).moveTo(SagaState.RESTING, now.plus(properties.limitOrderTtl()), now);
    }

    public void filledFromResting(UUID orderId) {
        reservedAndFilled(orderId);
    }

    public void rejected(UUID orderId) {
        Instant now = clock.instant();
        saga(orderId, now).moveTo(SagaState.REJECTED, null, now);
    }

    /**
     * Compensation: ask portfolio-service to release whatever it holds for the order.
     * Safe to repeat: portfolio releases only an ACTIVE reservation, never a settled one.
     */
    public void requestRelease(Order order, String reason) {
        Instant now = clock.instant();
        outboxWriter.append(Topics.ORDERS_CANCELLED, String.valueOf(order.getUserId()),
                new Events.OrderCancelled(order.getId(), order.getUserId(), reason));
        OrderSaga saga = saga(order.getId(), now);
        if (saga.getState() == SagaState.RELEASE_REQUESTED) {
            saga.redriven(now.plus(properties.releaseTimeout()), now);
        } else {
            saga.moveTo(SagaState.RELEASE_REQUESTED, now.plus(properties.releaseTimeout()), now);
        }
        saga.note(reason);
    }

    public void settled(Events.TradeSettled event) {
        Instant now = clock.instant();
        OrderSaga saga = saga(event.orderId(), now);
        if (saga.getState() == SagaState.AWAITING_SETTLEMENT || saga.getState() == SagaState.SETTLEMENT_FAILED) {
            saga.moveTo(SagaState.COMPLETED, null, now);
        } else if (saga.getState() != SagaState.COMPLETED) {
            log.warn("trades.settled for order {} in saga state {}", event.orderId(), saga.getState());
        }
    }

    public void released(Events.OrderReleased event) {
        Instant now = clock.instant();
        OrderSaga saga = saga(event.orderId(), now);
        if (event.outcome() == Events.ReleaseOutcome.ALREADY_SETTLED) {
            saga.fail(SagaState.RELEASE_FAILED, "portfolio reports the reservation was already settled by a trade",
                    now);
            log.error("Saga for order {} failed: release requested but reservation already settled", event.orderId());
            return;
        }
        if (saga.getState() == SagaState.RELEASE_REQUESTED || saga.getState() == SagaState.RELEASE_FAILED) {
            saga.moveTo(SagaState.RELEASED, null, now);
        }
    }

    /** Re-drive: re-publish the trade so portfolio settles it (or confirms it is already booked). */
    public void redriveSettlement(OrderSaga saga, Trade trade) {
        Instant now = clock.instant();
        outboxWriter.append(Topics.TRADES_EXECUTED, String.valueOf(trade.getUserId()),
                new Events.TradeExecuted(trade.getId(), trade.getOrderId(), trade.getUserId(), trade.getSymbol(),
                        trade.getSide(), trade.getQuantity(), trade.getPrice(), trade.getGrossAmount(),
                        trade.getExecutedAt()));
        saga.redriven(now.plus(properties.settlementTimeout()), now);
    }

    public void fail(OrderSaga saga, SagaState failedState, String error) {
        saga.fail(failedState, error, clock.instant());
        log.error("Saga for order {} moved to {}: {}", saga.getOrderId(), failedState, error);
    }

    /** Operator retry of a FAILED saga: resume the step it failed in, with a fresh re-drive budget. */
    public OrderSaga retry(UUID orderId) {
        Instant now = clock.instant();
        OrderSaga saga = saga(orderId, now);
        switch (saga.getState()) {
            case SETTLEMENT_FAILED -> saga.moveTo(SagaState.AWAITING_SETTLEMENT, now, now);
            case RELEASE_FAILED -> saga.moveTo(SagaState.RELEASE_REQUESTED, now, now);
            default -> throw com.luffy.tradeflow.common.web.ApiException.conflict("SAGA_NOT_FAILED",
                    "Only FAILED sagas can be retried; this one is " + saga.getState());
        }
        return saga;
    }

    private OrderSaga saga(UUID orderId, Instant now) {
        return repository.findById(orderId)
                .orElseGet(() -> repository.save(new OrderSaga(orderId, now.plus(properties.reservationTimeout()),
                        now)));
    }
}
