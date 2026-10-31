package com.luffy.tradeflow_order_service.service;

import com.luffy.tradeflow.common.events.Events;
import com.luffy.tradeflow.common.events.Topics;
import com.luffy.tradeflow.common.kafka.InconsistentStateException;
import com.luffy.tradeflow.common.outbox.OutboxWriter;
import com.luffy.tradeflow_order_service.domain.LatestPrice;
import com.luffy.tradeflow_order_service.domain.Order;
import com.luffy.tradeflow_order_service.domain.OrderStatus;
import com.luffy.tradeflow_order_service.domain.Touch;
import com.luffy.tradeflow_order_service.domain.Trade;
import com.luffy.tradeflow.common.events.Events.OrderType;
import com.luffy.tradeflow_order_service.repository.LatestPriceRepository;
import com.luffy.tradeflow_order_service.repository.OrderRepository;
import com.luffy.tradeflow_order_service.repository.TradeRepository;
import com.luffy.tradeflow_order_service.saga.OrderSagaService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * Simulated execution model (not an exchange matching engine), using the simulated touch:
 * buys execute at the ask, sells at the bid.
 * <ul>
 *   <li>MARKET orders fill in full at the ask/bid accepted when the order was placed (the price
 *       the funds were reserved at), as soon as the reservation succeeds.</li>
 *   <li>LIMIT orders fill in full once the ask (buy) / bid (sell) is at or better than the limit:
 *       immediately after reservation, or on a later prices.updated tick.</li>
 *   <li>SELL STOP / STOP_LIMIT orders rest until the bid falls to the trigger; a STOP then sells at
 *       the bid, a STOP_LIMIT becomes a limit sell.</li>
 * </ul>
 * Fills never exceed the reserved amount: a BUY fills at or below its reservation price.
 */
@Slf4j
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class ExecutionService {

    private final OrderRepository orderRepository;
    private final TradeRepository tradeRepository;
    private final LatestPriceRepository latestPriceRepository;
    private final OutboxWriter outboxWriter;
    private final OrderSagaService sagaService;
    private final Clock clock;

    public ExecutionService(OrderRepository orderRepository, TradeRepository tradeRepository,
                            LatestPriceRepository latestPriceRepository, OutboxWriter outboxWriter,
                            OrderSagaService sagaService, Clock clock) {
        this.orderRepository = orderRepository;
        this.tradeRepository = tradeRepository;
        this.latestPriceRepository = latestPriceRepository;
        this.outboxWriter = outboxWriter;
        this.sagaService = sagaService;
        this.clock = clock;
    }

    public void onReserved(Events.OrderReserved event) {
        Order order = load(event.orderId(), event.userId());
        if (order.getStatus() == OrderStatus.REJECTED || order.getStatus() == OrderStatus.CANCELLED
                || order.getStatus() == OrderStatus.EXPIRED) {
            // A reservation arrived after the order already ended (e.g. it timed out): compensate.
            log.warn("Late reservation for {} order {}; requesting release", order.getStatus(), order.getId());
            sagaService.requestRelease(order, "late reservation for " + order.getStatus() + " order");
            return;
        }
        if (order.getStatus() != OrderStatus.PENDING) {
            log.warn("Ignoring reservation for order {} in status {}", order.getId(), order.getStatus());
            return;
        }
        Instant now = clock.instant();
        if (order.getType() == OrderType.MARKET) {
            fill(order, order.getReservationPrice(), now);
            sagaService.reservedAndFilled(order.getId());
            return;
        }
        Optional<Touch> latest = latestPriceRepository.findById(order.getSymbol()).map(LatestPrice::touch);
        Optional<BigDecimal> fillPrice = latest.flatMap(t -> order.evaluate(t, now));
        if (fillPrice.isPresent()) {
            fill(order, fillPrice.get(), now);
            sagaService.reservedAndFilled(order.getId());
        } else {
            order.open(order.isStop() && order.getTriggeredAt() == null
                    ? "Shares reserved; waiting for the bid to fall to the stop " + order.getTriggerPrice()
                    : "Funds reserved; resting until the simulated price reaches " + order.getLimitPrice(), now);
            sagaService.reservedAndResting(order.getId());
        }
    }

    public void onRejected(Events.OrderRejected event) {
        Order order = load(event.orderId(), event.userId());
        if (order.getStatus() != OrderStatus.PENDING) {
            log.warn("Ignoring rejection for order {} in status {}", order.getId(), order.getStatus());
            return;
        }
        order.reject(event.reasonCode(), event.reason(), clock.instant());
        sagaService.rejected(order.getId());
        log.info("Order {} rejected: {} ({})", order.getId(), event.reasonCode(), event.reason());
    }

    /** Records the tick and fills any resting LIMIT / triggered stop orders it makes marketable. */
    public void onPriceUpdated(Events.PriceUpdated event) {
        LatestPrice latest = latestPriceRepository.findById(event.symbol()).orElse(null);
        if (latest == null) {
            latest = latestPriceRepository.save(new LatestPrice(event.symbol(), event.price(), event.tick(),
                    event.asOf(), event.bid(), event.ask()));
        } else if (!latest.update(event.price(), event.tick(), event.asOf(), event.bid(), event.ask())) {
            return;
        }
        Touch touch = latest.touch();
        Instant now = clock.instant();
        for (Order order : orderRepository.findBySymbolAndStatusAndTypeIn(event.symbol(), OrderStatus.OPEN,
                RESTING_TYPES)) {
            Optional<BigDecimal> fillPrice = order.evaluate(touch, now);
            if (fillPrice.isPresent()) {
                fill(order, fillPrice.get(), now);
                sagaService.filledFromResting(order.getId());
            }
        }
    }

    private static final java.util.Set<OrderType> RESTING_TYPES =
            java.util.EnumSet.of(OrderType.LIMIT, OrderType.STOP, OrderType.STOP_LIMIT);

    private void fill(Order order, BigDecimal price, Instant now) {
        Trade trade = Trade.execute(order, price, now);
        order.fill(trade.getPrice(), now);
        tradeRepository.save(trade);
        outboxWriter.append(Topics.TRADES_EXECUTED, String.valueOf(order.getUserId()),
                new Events.TradeExecuted(trade.getId(), order.getId(), order.getUserId(), order.getSymbol(),
                        order.getSide(), trade.getQuantity(), trade.getPrice(), trade.getGrossAmount(), now));
        log.info("Executed trade {} for order {}: {} {} x{} @ {}", trade.getId(), order.getId(), order.getSide(),
                order.getSymbol(), trade.getQuantity(), trade.getPrice());
    }

    private Order load(java.util.UUID orderId, long userId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new InconsistentStateException("Event refers to unknown order " + orderId));
        if (order.getUserId() != userId) {
            throw new InconsistentStateException("Event user " + userId + " does not own order " + orderId);
        }
        return order;
    }
}
