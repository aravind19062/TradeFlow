package com.luffy.tradeflow_live_trading_service.orders;

import com.luffy.tradeflow.common.web.ApiException;
import com.luffy.tradeflow_live_trading_service.audit.LiveAuditService;
import com.luffy.tradeflow_live_trading_service.broker.BrokerAdapter;
import com.luffy.tradeflow_live_trading_service.config.LiveProperties;
import com.luffy.tradeflow_live_trading_service.connection.BrokerConnection;
import com.luffy.tradeflow_live_trading_service.connection.BrokerConnectionService;
import com.luffy.tradeflow_live_trading_service.connection.CredentialVault;
import com.luffy.tradeflow_live_trading_service.risk.RiskLimitService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Real-money orders: preview → one-time confirmation bound to the exact parameters → place. Callers
 * must have passed {@code LiveAccessGuard}. Limits are re-checked under a per-user lock at
 * placement. The broker call happens outside any database transaction; an unclear outcome is
 * recorded as UNKNOWN, never retried automatically, and never assumed to have failed.
 */
@Service
public class LiveOrderService {

    private static final Logger log = LoggerFactory.getLogger(LiveOrderService.class);
    private static final Set<String> EXCHANGES = Set.of("NSE", "BSE");
    private static final Set<String> SIDES = Set.of("BUY", "SELL");
    private static final Set<String> TYPES = Set.of("MARKET", "LIMIT", "STOP", "STOP_LIMIT");
    private static final Pattern SYMBOL = Pattern.compile("[A-Z0-9&_-]{1,30}");
    private static final Pattern CLIENT_ID = Pattern.compile("[A-Za-z0-9-]{8,64}");
    /** Orders the broker definitely refused do not use up the daily limits; every other order does. */
    private static final Set<String> NOT_COUNTED = Set.of(LiveOrderStatus.REJECTED);

    public record Preview(UUID confirmationId, Instant expiresAt, OrderParams order, BigDecimal estimatedValue,
                          RiskLimitService.Limits limits, int ordersToday, BigDecimal turnoverToday, String warning) {
    }

    public record View(String id, String brokerOrderId, OrderParams order, BigDecimal orderValue, String status,
                       String statusMessage, long filledQuantity, BigDecimal averagePrice, Instant createdAt,
                       Instant updatedAt, String reviewReason, Instant lastReconciledAt) {
        static View of(LiveOrder o) {
            return new View(o.getId(), o.getBrokerOrderId(), o.params(), o.getOrderValue(), o.getStatus(),
                    o.getStatusMessage(), o.getFilledQuantity(), o.getAveragePrice(), o.getCreatedAt(), o.getUpdatedAt(),
                    o.getReviewReason(), o.getLastReconciledAt());
        }
    }

    private final LiveOrderRepository orders;
    private final OrderConfirmationRepository confirmations;
    private final BrokerConnectionService connections;
    private final CredentialVault vault;
    private final BrokerAdapter broker;
    private final RiskLimitService limits;
    private final LiveAuditService audit;
    private final LiveProperties properties;
    private final TransactionTemplate tx;
    private final Clock clock;

    public LiveOrderService(LiveOrderRepository orders, OrderConfirmationRepository confirmations,
                            BrokerConnectionService connections, CredentialVault vault, BrokerAdapter broker,
                            RiskLimitService limits, LiveAuditService audit, LiveProperties properties,
                            PlatformTransactionManager txManager, Clock clock) {
        this.orders = orders;
        this.confirmations = confirmations;
        this.connections = connections;
        this.vault = vault;
        this.broker = broker;
        this.limits = limits;
        this.audit = audit;
        this.properties = properties;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
    }

    // ------------------------------------------------------------------ preview

    public Preview preview(long userId, OrderParams raw) {
        OrderParams p = normalise(raw);
        requireNoUnresolvedOrder(userId);
        BrokerConnection c = connections.requireActive(userId);
        BigDecimal value = estimatedValue(c, p);
        Preview preview = tx.execute(s -> {
            RiskLimitService.Limits l = limits.lockAndGet(userId);
            Usage u = usage(userId);
            checkLimits(l, u, value);
            Instant now = clock.instant();
            OrderConfirmation conf = confirmations.save(new OrderConfirmation(userId, p.sha256(userId), value, now,
                    now.plus(properties.confirmationTtl())));
            return new Preview(conf.getId(), conf.getExpiresAt(), p, value, l, u.count(), u.turnover(),
                    "This is a REAL order with REAL money at your broker. Prices can move before it executes and "
                    + "you can lose money. TradeFlow does not give investment advice.");
        });
        audit.record(userId, "PREVIEW_ORDER", LiveAuditService.Outcome.ALLOWED, describe(p) + " value " + value, userId);
        return preview;
    }

    // ------------------------------------------------------------------ place

    public View place(long userId, UUID confirmationId, String clientOrderId, OrderParams raw) {
        OrderParams p = normalise(raw);
        if (clientOrderId == null || !CLIENT_ID.matcher(clientOrderId).matches()) {
            throw ApiException.badRequest("INVALID_CLIENT_ORDER_ID", "clientOrderId must be 8-64 letters, digits or '-'");
        }
        var existing = orders.findByUserIdAndClientOrderId(userId, clientOrderId);
        if (existing.isPresent()) { // idempotent retry of the same request
            if (!existing.get().params().sha256(userId).equals(p.sha256(userId))) {
                throw ApiException.conflict("CLIENT_ORDER_ID_REUSED", "This clientOrderId was used for a different order");
            }
            return View.of(existing.get());
        }
        LiveOrder order;
        try {
            order = tx.execute(s -> {
                RiskLimitService.Limits l = limits.lockAndGet(userId);
                // Under the per-user lock, so two concurrent placements cannot both pass.
                requireNoUnresolvedOrder(userId);
                OrderConfirmation conf = confirmations.lock(confirmationId == null ? new UUID(0, 0) : confirmationId)
                        .filter(x -> x.getUserId() == userId)
                        .orElseThrow(() -> ApiException.conflict("CONFIRMATION_INVALID", "Preview the order again"));
                Instant now = clock.instant();
                if (!conf.usable(now)) {
                    throw ApiException.conflict("CONFIRMATION_EXPIRED", "The confirmation expired or was used; preview again");
                }
                if (!conf.getParamsSha256().equals(p.sha256(userId))) {
                    throw ApiException.conflict("CONFIRMATION_MISMATCH",
                            "The order differs from what was previewed; preview again");
                }
                checkLimits(l, usage(userId), conf.getOrderValue());
                BrokerConnection c = connections.requireActive(userId);
                conf.consume(now);
                return orders.save(new LiveOrder(userId, c.getId(), clientOrderId, p, conf.getOrderValue(), conf.getId(), now));
            });
        } catch (ApiException refused) {
            audit.record(userId, "PLACE_ORDER", LiveAuditService.Outcome.REFUSED, refused.getCode() + " " + describe(p), userId);
            throw refused;
        }
        audit.record(userId, "PLACE_ORDER", LiveAuditService.Outcome.ALLOWED, order.getId() + " " + describe(p), userId);
        submit(order);
        return View.of(orders.findById(order.getId()).orElseThrow());
    }

    private void submit(LiveOrder order) {
        String status;
        String message;
        String brokerId = null;
        LiveAuditService.Outcome outcome;
        try {
            BrokerAdapter.BrokerOrderAck ack = broker.placeOrder(vault.accessToken(order.getConnectionId()),
                    new BrokerAdapter.OrderRequest(order.getClientOrderId(), order.getExchange(), order.getSymbol(),
                            order.getSide(), order.getOrderType(), order.getQuantity(), order.getLimitPrice(),
                            order.getTriggerPrice()));
            brokerId = ack.brokerOrderId();
            // An adapter must map the broker's vocabulary; anything else is "accepted, details to follow".
            status = LiveOrderStatus.FROM_BROKER.contains(ack.status()) ? ack.status() : LiveOrderStatus.OPEN;
            message = ack.message();
            outcome = LiveAuditService.Outcome.SUBMITTED;
            if (brokerId == null || brokerId.isBlank()) { // accepted without an id: we cannot manage it
                brokerId = null;
                status = LiveOrderStatus.UNKNOWN;
                message = "The broker did not return an order id. Check your broker account before trying again.";
                outcome = LiveAuditService.Outcome.UNKNOWN;
            }
        } catch (ApiException e) {
            boolean definite = e.getStatus().is4xxClientError();
            status = definite ? "REJECTED" : "UNKNOWN";
            message = definite ? e.getMessage()
                    : "The broker did not confirm this order. Check your broker account before trying again.";
            outcome = definite ? LiveAuditService.Outcome.REJECTED : LiveAuditService.Outcome.UNKNOWN;
        } catch (RuntimeException e) {
            log.warn("Live order {} submission outcome unknown ({})", order.getId(), e.getClass().getSimpleName());
            status = "UNKNOWN";
            message = "The broker did not confirm this order. Check your broker account before trying again.";
            outcome = LiveAuditService.Outcome.UNKNOWN;
        }
        final String fStatus = status, fMessage = message, fBrokerId = brokerId;
        String applied = tx.execute(s -> {
            LiveOrder o = orders.findById(order.getId()).orElseThrow();
            if (!LiveOrderStatus.SUBMITTING.equals(o.getStatus())) {
                // A very late answer: reconciliation has already decided from the broker's order book.
                // Keep that decision; only an id we did not know yet, or a contradicting one, matters.
                if (fBrokerId != null && o.getBrokerOrderId() == null && !LiveOrderStatus.TERMINAL.contains(o.getStatus())) {
                    o.acknowledged(fBrokerId, fStatus, fMessage, clock.instant());
                    return "late ack applied";
                }
                if (fBrokerId != null && o.getBrokerOrderId() != null && !fBrokerId.equals(o.getBrokerOrderId())) {
                    o.needsReview(OrderReconciler.ID_MISMATCH, "The broker reported two different order ids for this "
                            + "order. Check your broker account.", clock.instant());
                    return "late ack contradicts broker id " + o.getBrokerOrderId();
                }
                return "late answer ignored (already " + o.getStatus() + ")";
            }
            if (fBrokerId != null) {
                o.acknowledged(fBrokerId, fStatus, fMessage, clock.instant());
            } else {
                o.failed(fStatus, fMessage, clock.instant());
            }
            return null;
        });
        audit.record(order.getUserId(), "SUBMIT_ORDER", outcome, order.getId() + " broker " + brokerId + " " + status
                + (applied == null ? "" : " (" + applied + ")"), order.getUserId());
    }

    /**
     * An order whose outcome is not established blocks new orders (see {@link LiveOrderStatus#UNRESOLVED}),
     * so a user who cannot tell whether an order went through cannot accidentally place it twice.
     */
    private void requireNoUnresolvedOrder(long userId) {
        if (orders.existsByUserIdAndStatusIn(userId, LiveOrderStatus.UNRESOLVED)) {
            throw ApiException.conflict("UNRESOLVED_ORDER", "An earlier live order's outcome at your broker is not "
                    + "confirmed yet. TradeFlow is checking your broker's order book; new orders are blocked until "
                    + "it is resolved. It is never resubmitted automatically.");
        }
    }

    /**
     * NEEDS_REVIEW → RESOLVED_BY_USER: the user checked their broker account and dealt with the order
     * there. Callers must have passed the step-up guard. Nothing is sent to the broker. The order keeps
     * counting toward the daily limits (it may have executed).
     */
    public View resolve(long userId, String orderId, String authentication) {
        String detail = tx.execute(s -> {
            LiveOrder o = orders.findByIdAndUserId(orderId, userId).orElseThrow(() -> ApiException.notFound("Order " + orderId));
            if (!LiveOrderStatus.NEEDS_REVIEW.equals(o.getStatus())) {
                throw ApiException.conflict("NOT_UNDER_REVIEW", "Only orders that need your review can be resolved here");
            }
            String reason = o.getReviewReason();
            o.resolvedByUser("You confirmed you checked this order in your broker account (" + reason + ").", clock.instant());
            return orderId + " " + reason + " [" + authentication + "]";
        });
        audit.record(userId, "RESOLVE_ORDER", LiveAuditService.Outcome.ALLOWED, detail, userId);
        return View.of(orders.findById(orderId).orElseThrow());
    }

    // ------------------------------------------------------------------ cancel / read

    /** Allowed even when readiness was revoked or trading is halted (D5): users can always manage open orders. */
    public View cancel(long userId, String orderId) {
        LiveOrder o = orders.findByIdAndUserId(orderId, userId).orElseThrow(() -> ApiException.notFound("Order " + orderId));
        if (o.getBrokerOrderId() == null) {
            throw ApiException.conflict("NOT_AT_BROKER", "This order has no broker order id to cancel");
        }
        BrokerConnection c = connections.requireActive(userId);
        BrokerAdapter.BrokerOrderAck ack;
        try {
            ack = broker.cancelOrder(vault.accessToken(c.getId()), o.getBrokerOrderId());
        } catch (ApiException e) {
            audit.record(userId, "CANCEL_ORDER", LiveAuditService.Outcome.FAILED, orderId + " " + e.getCode(), userId);
            throw e;
        }
        tx.executeWithoutResult(s -> orders.findById(orderId).orElseThrow()
                .cancelRequested(ack.message() == null ? "Cancellation requested" : ack.message(), clock.instant()));
        audit.record(userId, "CANCEL_ORDER", LiveAuditService.Outcome.ALLOWED, orderId + " broker " + o.getBrokerOrderId(),
                userId);
        return View.of(orders.findById(orderId).orElseThrow());
    }

    @Transactional(readOnly = true)
    public List<View> list(long userId) {
        return orders.findByUserIdOrderByCreatedAtDesc(userId).stream().map(View::of).toList();
    }

    @Transactional(readOnly = true)
    public View get(long userId, String orderId) {
        return orders.findByIdAndUserId(orderId, userId).map(View::of)
                .orElseThrow(() -> ApiException.notFound("Order " + orderId));
    }

    // ------------------------------------------------------------------ helpers

    record Usage(int count, BigDecimal turnover) {
    }

    private Usage usage(long userId) {
        List<LiveOrder> today = orders.findByUserIdAndCreatedAtAfter(userId, limits.startOfTradingDay()).stream()
                .filter(o -> !NOT_COUNTED.contains(o.getStatus())).toList();
        return new Usage(today.size(), today.stream().map(LiveOrder::getOrderValue).reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    private static void checkLimits(RiskLimitService.Limits l, Usage u, BigDecimal value) {
        if (value.compareTo(l.maxOrderValue()) > 0) {
            throw ApiException.unprocessable("MAX_ORDER_VALUE", "Order value " + value.toPlainString()
                    + " exceeds your limit of " + l.maxOrderValue().toPlainString());
        }
        if (u.count() >= l.maxOrdersPerDay()) {
            throw ApiException.unprocessable("MAX_ORDERS_PER_DAY", "Daily order limit (" + l.maxOrdersPerDay() + ") reached");
        }
        if (u.turnover().add(value).compareTo(l.maxDailyTurnover()) > 0) {
            throw ApiException.unprocessable("MAX_DAILY_TURNOVER", "This order would exceed your daily turnover limit of "
                    + l.maxDailyTurnover().toPlainString());
        }
    }

    private BigDecimal estimatedValue(BrokerConnection c, OrderParams p) {
        BigDecimal price = switch (p.type()) {
            case "LIMIT", "STOP_LIMIT" -> p.limitPrice();
            case "STOP" -> p.triggerPrice();
            default -> {
                List<BrokerAdapter.Quote> q = broker.quotes(vault.accessToken(c.getId()), List.of(p.exchange() + ":" + p.symbol()));
                BrokerAdapter.Quote quote = q.stream().findFirst().orElseThrow(() ->
                        new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "QUOTE_UNAVAILABLE", "No broker quote for " + p.symbol()));
                BigDecimal side = "BUY".equals(p.side()) ? quote.ask() : quote.bid();
                yield side != null ? side : quote.lastPrice();
            }
        };
        return price.multiply(BigDecimal.valueOf(p.quantity())).setScale(2, RoundingMode.HALF_UP);
    }

    static OrderParams normalise(OrderParams p) {
        if (p == null) {
            throw ApiException.badRequest("INVALID_ORDER", "Order details are required");
        }
        String exchange = upper(p.exchange());
        String symbol = upper(p.symbol());
        String side = upper(p.side());
        String type = upper(p.type());
        if (!EXCHANGES.contains(exchange)) throw ApiException.badRequest("INVALID_EXCHANGE", "Exchange must be NSE or BSE");
        if (symbol == null || !SYMBOL.matcher(symbol).matches()) throw ApiException.badRequest("INVALID_SYMBOL", "Invalid symbol");
        if (!SIDES.contains(side)) throw ApiException.badRequest("INVALID_SIDE", "Side must be BUY or SELL");
        if (!TYPES.contains(type)) throw ApiException.badRequest("INVALID_TYPE", "Type must be MARKET, LIMIT, STOP or STOP_LIMIT");
        if (p.quantity() < 1 || p.quantity() > 100_000) throw ApiException.badRequest("INVALID_QUANTITY", "Quantity must be 1-100000");
        boolean needsLimit = type.equals("LIMIT") || type.equals("STOP_LIMIT");
        boolean needsTrigger = type.equals("STOP") || type.equals("STOP_LIMIT");
        BigDecimal limit = price(p.limitPrice(), needsLimit, "limitPrice");
        BigDecimal trigger = price(p.triggerPrice(), needsTrigger, "triggerPrice");
        return new OrderParams(exchange, symbol, side, type, p.quantity(), limit, trigger);
    }

    private static BigDecimal price(BigDecimal v, boolean required, String name) {
        if (!required) {
            if (v != null) throw ApiException.badRequest("UNEXPECTED_PRICE", name + " is not used by this order type");
            return null;
        }
        if (v == null || v.signum() <= 0 || v.scale() > 2) {
            throw ApiException.badRequest("INVALID_PRICE", name + " must be positive with at most 2 decimals");
        }
        return v.setScale(2, RoundingMode.UNNECESSARY);
    }

    private static String upper(String s) {
        return s == null ? null : s.trim().toUpperCase(java.util.Locale.ROOT);
    }

    private static String describe(OrderParams p) {
        return p.side() + " " + p.quantity() + " " + p.exchange() + ":" + p.symbol() + " " + p.type()
                + (p.limitPrice() == null ? "" : " @" + p.limitPrice()) + (p.triggerPrice() == null ? "" : " trg " + p.triggerPrice());
    }
}
