package com.luffy.tradeflow_order_service.service;

import com.luffy.tradeflow.common.events.Events;
import com.luffy.tradeflow.common.events.Topics;
import com.luffy.tradeflow.common.kafka.InconsistentStateException;
import com.luffy.tradeflow.common.outbox.OutboxWriter;
import com.luffy.tradeflow_order_service.domain.LatestPrice;
import com.luffy.tradeflow_order_service.domain.Order;
import com.luffy.tradeflow_order_service.domain.OrderStatus;
import com.luffy.tradeflow_order_service.domain.Trade;
import com.luffy.tradeflow.common.events.Events.OrderType;
import com.luffy.tradeflow_order_service.repository.LatestPriceRepository;
import com.luffy.tradeflow_order_service.repository.OrderRepository;
import com.luffy.tradeflow_order_service.repository.TradeRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * Simulated execution model (not an exchange matching engine):
 * <ul>
 *   <li>MARKET orders fill in full at the quote accepted when the order was placed
 *       (the price the funds were reserved at), as soon as the reservation succeeds.</li>
 *   <li>LIMIT orders fill in full at the latest simulated price once it is at or better
 *       than the limit: immediately after reservation, or on a later prices.updated tick.</li>
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
    private final Clock clock;

    public ExecutionService(OrderRepository orderRepository, TradeRepository tradeRepository,
                            LatestPriceRepository latestPriceRepository, OutboxWriter outboxWriter, Clock clock) {
        this.orderRepository = orderRepository;
        this.tradeRepository = tradeRepository;
        this.latestPriceRepository = latestPriceRepository;
        this.outboxWriter = outboxWriter;
        this.clock = clock;
    }

    public void onReserved(Events.OrderReserved event) {
        Order order = load(event.orderId(), event.userId());
        if (order.getStatus() != OrderStatus.PENDING) {
            log.warn("Ignoring reservation for order {} in status {}", order.getId(), order.getStatus());
            return;
        }
        Instant now = clock.instant();
        if (order.getType() == OrderType.MARKET) {
            fill(order, order.getReservationPrice(), now);
            return;
        }
        Optional<BigDecimal> latest = latestPriceRepository.findById(order.getSymbol()).map(LatestPrice::getPrice);
        if (latest.isPresent() && order.isMarketableAt(latest.get())) {
            fill(order, latest.get(), now);
        } else {
            order.open("Funds reserved; resting until the simulated price reaches " + order.getLimitPrice(), now);
        }
    }

    public void onRejected(Events.OrderRejected event) {
        Order order = load(event.orderId(), event.userId());
        if (order.getStatus() != OrderStatus.PENDING) {
            log.warn("Ignoring rejection for order {} in status {}", order.getId(), order.getStatus());
            return;
        }
        order.reject(event.reasonCode(), event.reason(), clock.instant());
        log.info("Order {} rejected: {} ({})", order.getId(), event.reasonCode(), event.reason());
    }

    /** Records the tick and fills any resting LIMIT orders it makes marketable. */
    public void onPriceUpdated(Events.PriceUpdated event) {
        LatestPrice latest = latestPriceRepository.findById(event.symbol()).orElse(null);
        if (latest == null) {
            latestPriceRepository.save(new LatestPrice(event.symbol(), event.price(), event.tick(), event.asOf()));
        } else if (!latest.update(event.price(), event.tick(), event.asOf())) {
            return;
        }
        Instant now = clock.instant();
        for (Order order : orderRepository.findBySymbolAndStatusAndType(event.symbol(), OrderStatus.OPEN,
                OrderType.LIMIT)) {
            if (order.isMarketableAt(event.price())) {
                fill(order, event.price(), now);
            }
        }
    }

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
