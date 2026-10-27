package com.luffy.tradeflow_order_service.service;

import com.luffy.tradeflow.common.events.Events;
import com.luffy.tradeflow.common.events.Events.OrderSide;
import com.luffy.tradeflow.common.events.Events.OrderType;
import com.luffy.tradeflow.common.events.Topics;
import com.luffy.tradeflow.common.money.Money;
import com.luffy.tradeflow.common.outbox.OutboxWriter;
import com.luffy.tradeflow.common.security.AuthenticatedUser;
import com.luffy.tradeflow.common.web.ApiException;
import com.luffy.tradeflow_order_service.client.MarketDataClient;
import com.luffy.tradeflow_order_service.config.OrderProperties;
import com.luffy.tradeflow_order_service.domain.Order;
import com.luffy.tradeflow_order_service.domain.OrderStatus;
import com.luffy.tradeflow_order_service.domain.Trade;
import com.luffy.tradeflow_order_service.repository.OrderRepository;
import com.luffy.tradeflow_order_service.repository.TradeRepository;
import com.luffy.tradeflow_order_service.web.OrderDtos.OrderDetailResponse;
import com.luffy.tradeflow_order_service.web.OrderDtos.OrderResponse;
import com.luffy.tradeflow_order_service.web.OrderDtos.PlaceOrderRequest;
import com.luffy.tradeflow_order_service.web.OrderDtos.StatusChangeResponse;
import com.luffy.tradeflow_order_service.web.OrderDtos.TradeResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Accepts, queries and cancels orders. Acceptance only validates and records the
 * order; funds are reserved asynchronously by portfolio-service, so a new order
 * is returned as PENDING (HTTP 202) and moves on as events arrive.
 */
@Slf4j
@Service
public class OrderService {

    public static final int MAX_PAGE_SIZE = 100;

    private final OrderRepository orderRepository;
    private final TradeRepository tradeRepository;
    private final MarketDataClient marketDataClient;
    private final OutboxWriter outboxWriter;
    private final OrderProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public OrderService(OrderRepository orderRepository, TradeRepository tradeRepository,
                        MarketDataClient marketDataClient, OutboxWriter outboxWriter, OrderProperties properties,
                        TransactionTemplate transactionTemplate, Clock clock) {
        this.orderRepository = orderRepository;
        this.tradeRepository = tradeRepository;
        this.marketDataClient = marketDataClient;
        this.outboxWriter = outboxWriter;
        this.properties = properties;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    public record PlacementResult(OrderResponse order, boolean created) {
    }

    public PlacementResult place(AuthenticatedUser user, PlaceOrderRequest request) {
        String symbol = request.symbol().trim().toUpperCase(Locale.ROOT);
        validateShape(request);
        if (request.clientOrderId() != null) {
            var existing = orderRepository.findByUserIdAndClientOrderId(user.id(), request.clientOrderId());
            if (existing.isPresent()) {
                return replay(existing.get(), symbol, request);
            }
        }
        // Network call deliberately outside the transaction: no DB connection is held while waiting.
        MarketDataClient.Quote quote = marketDataClient.quote(symbol);
        BigDecimal reservationPrice = request.type() == OrderType.MARKET
                ? Money.price(quote.price())
                : Money.price(request.limitPrice());
        BigDecimal notional = Money.amount(request.quantity(), reservationPrice);
        if (notional.compareTo(properties.maxNotional()) > 0) {
            throw ApiException.unprocessable("ORDER_TOO_LARGE",
                    "Order value " + notional + " exceeds the per-order limit of " + properties.maxNotional());
        }
        try {
            Order saved = transactionTemplate.execute(status -> {
                Order order = Order.place(user.id(), request.clientOrderId(), symbol, request.side(), request.type(),
                        request.quantity(), request.limitPrice() == null ? null : Money.price(request.limitPrice()),
                        reservationPrice, clock.instant());
                orderRepository.save(order);
                outboxWriter.append(Topics.ORDERS_PLACED, String.valueOf(user.id()),
                        new Events.OrderPlaced(order.getId(), user.id(), symbol, order.getSide(), order.getType(),
                                order.getQuantity(), reservationPrice));
                return order;
            });
            log.info("Accepted {} {} {} x{} for user {} as order {}", request.type(), request.side(), symbol,
                    request.quantity(), user.id(), Objects.requireNonNull(saved).getId());
            return new PlacementResult(OrderResponse.from(saved), true);
        } catch (DataIntegrityViolationException e) {
            // Two concurrent submissions with the same clientOrderId: the unique constraint picked a winner.
            if (request.clientOrderId() != null) {
                return orderRepository.findByUserIdAndClientOrderId(user.id(), request.clientOrderId())
                        .map(o -> replay(o, symbol, request))
                        .orElseThrow(() -> e);
            }
            throw e;
        }
    }

    @Transactional(readOnly = true)
    public OrderDetailResponse get(AuthenticatedUser user, UUID orderId) {
        Order order = findOwned(user, orderId);
        return new OrderDetailResponse(OrderResponse.from(order),
                order.getHistory().stream().map(StatusChangeResponse::from).toList(),
                tradeRepository.findByOrderIdOrderByExecutedAtAsc(orderId).stream().map(TradeResponse::from).toList());
    }

    @Transactional(readOnly = true)
    public Page<Order> list(AuthenticatedUser user, OrderStatus status, String symbol, OrderSide side,
                            Instant from, Instant to, int page, int size) {
        Specification<Order> spec = (root, query, cb) -> cb.equal(root.get("userId"), user.id());
        if (status != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        if (symbol != null && !symbol.isBlank()) {
            String normalized = symbol.trim().toUpperCase(Locale.ROOT);
            spec = spec.and((root, query, cb) -> cb.equal(root.get("symbol"), normalized));
        }
        if (side != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("side"), side));
        }
        if (from != null) {
            spec = spec.and((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("createdAt"), from));
        }
        if (to != null) {
            spec = spec.and((root, query, cb) -> cb.lessThan(root.get("createdAt"), to));
        }
        return orderRepository.findAll(spec, pageRequest(page, size, "createdAt"));
    }

    @Transactional(readOnly = true)
    public Page<Trade> trades(AuthenticatedUser user, String symbol, int page, int size) {
        PageRequest pageable = pageRequest(page, size, "executedAt");
        return symbol == null || symbol.isBlank()
                ? tradeRepository.findByUserId(user.id(), pageable)
                : tradeRepository.findByUserIdAndSymbol(user.id(), symbol.trim().toUpperCase(Locale.ROOT), pageable);
    }

    /** Cancels an OPEN order and asks portfolio-service to release its reservation. */
    @Transactional
    public OrderResponse cancel(AuthenticatedUser user, UUID orderId) {
        Order order = findOwned(user, orderId);
        order.cancel("Cancelled by user", clock.instant());
        outboxWriter.append(Topics.ORDERS_CANCELLED, String.valueOf(user.id()),
                new Events.OrderCancelled(order.getId(), user.id(), "Cancelled by user"));
        log.info("Order {} cancelled by user {}", orderId, user.id());
        return OrderResponse.from(order);
    }

    /** Another user's order is reported as not found, so order ids cannot be probed. */
    private Order findOwned(AuthenticatedUser user, UUID orderId) {
        return orderRepository.findByIdAndUserId(orderId, user.id())
                .orElseThrow(() -> ApiException.notFound("Order " + orderId));
    }

    private void validateShape(PlaceOrderRequest request) {
        if (request.type() == OrderType.LIMIT && request.limitPrice() == null) {
            throw ApiException.badRequest("LIMIT_PRICE_REQUIRED", "LIMIT orders require limitPrice");
        }
        if (request.type() == OrderType.MARKET && request.limitPrice() != null) {
            throw ApiException.badRequest("LIMIT_PRICE_NOT_ALLOWED", "MARKET orders must not specify limitPrice");
        }
        if (request.quantity() > properties.maxQuantity()) {
            throw ApiException.unprocessable("QUANTITY_TOO_LARGE",
                    "Quantity must not exceed " + properties.maxQuantity());
        }
    }

    private PlacementResult replay(Order existing, String symbol, PlaceOrderRequest request) {
        boolean same = existing.getSymbol().equals(symbol)
                && existing.getSide() == request.side()
                && existing.getType() == request.type()
                && existing.getQuantity() == request.quantity()
                && (existing.getLimitPrice() == null
                ? request.limitPrice() == null
                : request.limitPrice() != null && existing.getLimitPrice().compareTo(request.limitPrice()) == 0);
        if (!same) {
            throw ApiException.conflict("IDEMPOTENCY_KEY_REUSED",
                    "clientOrderId " + request.clientOrderId() + " was already used for a different order");
        }
        return new PlacementResult(OrderResponse.from(existing), false);
    }

    private static PageRequest pageRequest(int page, int size, String sortProperty) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw ApiException.badRequest("INVALID_PAGE", "page must be >= 0 and size between 1 and " + MAX_PAGE_SIZE);
        }
        return PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, sortProperty));
    }
}
