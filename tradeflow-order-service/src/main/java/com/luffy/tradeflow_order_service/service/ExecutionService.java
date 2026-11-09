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
import com.luffy.tradeflow.common.events.Events.OrderSide;
import com.luffy.tradeflow.common.events.Events.OrderType;
import com.luffy.tradeflow_order_service.matching.MatchingProperties;
import com.luffy.tradeflow_order_service.matching.SimulatedOrderBook;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The DEMO matching engine. Orders execute against a {@link SimulatedOrderBook}: limited shares at
 * the touch and a few worse prices, renewed on every tick. Buys take the asks, sells take the bids.
 * <ul>
 *   <li>MARKET orders first execute at the quote the user accepted, as soon as the reservation
 *       succeeds. Any remainder works on later ticks until it fills or its short time limit expires
 *       (INSUFFICIENT_LIQUIDITY).</li>
 *   <li>LIMIT orders take what is available at or better than the limit, immediately after
 *       reservation or on later ticks.</li>
 *   <li>SELL STOP / STOP_LIMIT orders rest until the bid falls to the trigger; a STOP then sells into
 *       the book, a STOP_LIMIT becomes a limit sell.</li>
 * </ul>
 * Fills never exceed the reserved amount: a BUY executes at or below its reservation price. On each
 * tick the best-priced order goes first, then the oldest. Live orders never come here: they belong
 * to live-trading-service, which goes to the broker and shares no code with this service.
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
    private final MatchingProperties matching;
    private final Clock clock;

    public ExecutionService(OrderRepository orderRepository, TradeRepository tradeRepository,
                            LatestPriceRepository latestPriceRepository, OutboxWriter outboxWriter,
                            OrderSagaService sagaService, MatchingProperties matching,
                            Clock clock) {
        this.orderRepository = orderRepository;
        this.tradeRepository = tradeRepository;
        this.latestPriceRepository = latestPriceRepository;
        this.outboxWriter = outboxWriter;
        this.sagaService = sagaService;
        this.matching = matching;
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
        marketFor(order, now).ifPresent(market -> match(order, market, firstTouch(order, market), now));
        if (order.getStatus() == OrderStatus.FILLED) {
            sagaService.reservedAndFilled(order.getId());
            return;
        }
        if (order.getStatus() == OrderStatus.PENDING) {
            order.open(waitingReason(order), now);
        }
        sagaService.reservedAndResting(order);
    }

    /** The symbol's book, locked. A MARKET order arriving before any tick uses its accepted quote. */
    private Optional<LatestPrice> marketFor(Order order, Instant now) {
        Optional<LatestPrice> market = latestPriceRepository.findForUpdate(order.getSymbol());
        if (market.isPresent() || order.getType() != OrderType.MARKET) {
            return market;
        }
        BigDecimal quoted = order.getQuotedPrice() != null ? order.getQuotedPrice() : order.getReservationPrice();
        return Optional.of(latestPriceRepository.save(
                new LatestPrice(order.getSymbol(), quoted, 0, now, quoted, quoted)));
    }

    /** A MARKET order's first execution uses the quote the user accepted, the freshest price it has. */
    private static Touch firstTouch(Order order, LatestPrice market) {
        return order.getQuotedPrice() != null ? Touch.flat(order.getQuotedPrice()) : market.touch();
    }

    private static String waitingReason(Order order) {
        if (order.getType() == OrderType.MARKET) {
            return "Reserved; waiting for liquidity at or better than " + order.getReservationPrice();
        }
        return order.isStop() && order.getTriggeredAt() == null
                ? "Shares reserved; waiting for the bid to fall to the stop " + order.getTriggerPrice()
                : "Funds reserved; resting until the simulated price reaches " + order.getLimitPrice();
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

    /** Records the tick, renewing the book, and matches every working order against it. */
    public void onPriceUpdated(Events.PriceUpdated event) {
        LatestPrice latest = latestPriceRepository.findForUpdate(event.symbol()).orElse(null);
        if (latest == null) {
            latest = latestPriceRepository.save(new LatestPrice(event.symbol(), event.price(), event.tick(),
                    event.asOf(), event.bid(), event.ask()));
        } else if (!latest.update(event.price(), event.tick(), event.asOf(), event.bid(), event.ask())) {
            return;
        }
        Instant now = clock.instant();
        List<Order> working = new ArrayList<>(orderRepository.findBySymbolAndStatusInAndTypeIn(event.symbol(),
                WORKING, EnumSet.allOf(OrderType.class)));
        working.sort(PRICE_TIME_PRIORITY);
        for (Order order : working) {
            match(order, latest, latest.touch(), now);
            if (order.getStatus() == OrderStatus.FILLED) {
                sagaService.filledFromResting(order.getId());
            }
        }
    }

    private static final Set<OrderStatus> WORKING = EnumSet.of(OrderStatus.OPEN, OrderStatus.PARTIALLY_FILLED);

    /** Orders with no limit first, then the highest bids and lowest offers, then the oldest. */
    private static final Comparator<Order> PRICE_TIME_PRIORITY = Comparator
            .comparing((Order o) -> o.getLimitPrice() != null)
            .thenComparing(o -> o.getLimitPrice() == null ? BigDecimal.ZERO
                    : o.getSide() == OrderSide.BUY ? o.getLimitPrice().negate() : o.getLimitPrice())
            .thenComparing(Order::getCreatedAt);

    private void match(Order order, LatestPrice market, Touch touch, Instant now) {
        if (order.evaluate(touch, now).isEmpty()) {
            return;
        }
        OrderSide side = order.getSide();
        SimulatedOrderBook book = new SimulatedOrderBook(order.getSymbol(), market.getTick(), side,
                side == OrderSide.BUY ? touch.ask() : touch.bid(), market.consumed(side), matching);
        book.take(order.remainingQuantity(), order::isMarketableAt)
                .forEach(slice -> execute(order, slice.quantity(), slice.price(), now));
        market.recordConsumed(side, book.consumed());
    }

    private void execute(Order order, long quantity, BigDecimal price, Instant now) {
        Trade trade = Trade.execute(order, quantity, price, now);
        order.fill(quantity, trade.getPrice(), now);
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
