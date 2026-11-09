package com.luffy.tradeflow_order_service.saga;

import com.luffy.tradeflow.common.events.Events;
import com.luffy.tradeflow.common.events.Topics;
import com.luffy.tradeflow.common.outbox.OutboxWriter;
import com.luffy.tradeflow_order_service.domain.Order;
import com.luffy.tradeflow_order_service.domain.OrderStatus;
import com.luffy.tradeflow_order_service.domain.Trade;
import com.luffy.tradeflow_order_service.repository.OrderRepository;
import com.luffy.tradeflow_order_service.repository.TradeRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * All saga transitions. Every method joins the caller's transaction, so the saga
 * record, the order and any outbox event (compensation or re-drive) commit together.
 */
@Slf4j
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class OrderSagaService {

    private static final Set<SagaState> COMPATIBLE_WITH_SETTLEMENT =
            EnumSet.of(SagaState.RESTING, SagaState.RELEASE_REQUESTED, SagaState.COMPLETED, SagaState.RELEASED);

    private final OrderSagaRepository repository;
    private final OrderRepository orderRepository;
    private final TradeRepository tradeRepository;
    private final OutboxWriter outboxWriter;
    private final SagaProperties properties;
    private final Clock clock;

    public OrderSagaService(OrderSagaRepository repository, OrderRepository orderRepository,
                            TradeRepository tradeRepository, OutboxWriter outboxWriter, SagaProperties properties,
                            Clock clock) {
        this.repository = repository;
        this.orderRepository = orderRepository;
        this.tradeRepository = tradeRepository;
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

    /** The order keeps working: a MARKET remainder gets a short time limit, anything else the long one. */
    public void reservedAndResting(Order order) {
        Instant now = clock.instant();
        saga(order.getId(), now).moveTo(SagaState.RESTING, now.plus(order.getType() == Events.OrderType.MARKET
                ? properties.marketOrderTtl() : properties.limitOrderTtl()), now);
    }

    public void filledFromResting(UUID orderId) {
        reservedAndFilled(orderId);
    }

    public void rejected(UUID orderId) {
        Instant now = clock.instant();
        saga(orderId, now).moveTo(SagaState.REJECTED, null, now);
    }

    /**
     * Compensation: ask portfolio-service to release whatever the order no longer needs. The event
     * carries the quantity already executed, because a trade can still be on its way on another
     * topic and portfolio must keep that trade's funds. Safe to repeat.
     */
    public void requestRelease(Order order, String reason) {
        Instant now = clock.instant();
        outboxWriter.append(Topics.ORDERS_CANCELLED, String.valueOf(order.getUserId()),
                new Events.OrderCancelled(order.getId(), order.getUserId(), reason, order.getFilledQuantity()));
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
        tradeRepository.findById(event.tradeId()).ifPresent(trade -> trade.markSettled(now));
        OrderSaga saga = saga(event.orderId(), now);
        // While RESTING or RELEASE_REQUESTED an order can have partial trades settling alongside.
        if (saga.getState() == SagaState.AWAITING_SETTLEMENT || saga.getState() == SagaState.SETTLEMENT_FAILED) {
            if (!tradeRepository.existsByOrderIdAndSettledAtIsNull(event.orderId())) {
                finishSettlement(saga);
            }
        } else if (!COMPATIBLE_WITH_SETTLEMENT.contains(saga.getState())) {
            log.warn("trades.settled for order {} in saga state {}", event.orderId(), saga.getState());
        }
    }

    /** Every trade is booked: a full fill is complete; otherwise the remainder was already released. */
    public void finishSettlement(OrderSaga saga) {
        boolean filled = orderRepository.findById(saga.getOrderId())
                .map(order -> order.getStatus() == OrderStatus.FILLED).orElse(true);
        saga.moveTo(filled ? SagaState.COMPLETED : SagaState.RELEASED, null, clock.instant());
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
            if (tradeRepository.existsByOrderIdAndSettledAtIsNull(event.orderId())) {
                saga.moveTo(SagaState.AWAITING_SETTLEMENT, now.plus(properties.settlementTimeout()), now);
            } else {
                saga.moveTo(SagaState.RELEASED, null, now);
            }
        }
    }

    /** Re-drive: re-publish the trades so portfolio settles them (or confirms they are already booked). */
    public void redriveSettlement(OrderSaga saga, List<Trade> trades) {
        Instant now = clock.instant();
        for (Trade trade : trades) {
            outboxWriter.append(Topics.TRADES_EXECUTED, String.valueOf(trade.getUserId()),
                    new Events.TradeExecuted(trade.getId(), trade.getOrderId(), trade.getUserId(), trade.getSymbol(),
                            trade.getSide(), trade.getQuantity(), trade.getPrice(), trade.getGrossAmount(),
                            trade.getExecutedAt()));
        }
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
