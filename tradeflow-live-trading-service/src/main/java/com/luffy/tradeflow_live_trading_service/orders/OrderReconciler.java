package com.luffy.tradeflow_live_trading_service.orders;

import com.luffy.tradeflow_live_trading_service.audit.LiveAuditService;
import com.luffy.tradeflow_live_trading_service.broker.BrokerAdapter;
import com.luffy.tradeflow_live_trading_service.config.LiveProperties;
import com.luffy.tradeflow_live_trading_service.connection.BrokerConnection;
import com.luffy.tradeflow_live_trading_service.connection.BrokerConnectionRepository;
import com.luffy.tradeflow_live_trading_service.connection.BrokerConnectionService;
import com.luffy.tradeflow_live_trading_service.connection.CredentialVault;
import com.luffy.tradeflow_live_trading_service.notifications.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Brings TradeFlow's live orders in line with the broker's own order book. It only READS from the
 * broker: it never places, resubmits or cancels anything. Orders of unknown outcome are looked up by
 * the clientOrderId TradeFlow sent with them (the broker's order tag), never by a fuzzy match on
 * symbol/quantity/price, because a wrong match could hide a real duplicate.
 *
 * <ul>
 *   <li>found once: the order takes the broker's id and state;</li>
 *   <li>found more than once: NEEDS_REVIEW (DUPLICATE_AT_BROKER); the user decides at the broker;</li>
 *   <li>not found: stays UNKNOWN for the absence window (brokers can list new orders late), then
 *       NEEDS_REVIEW (NOT_FOUND_AT_BROKER);</li>
 *   <li>broker unreachable: nothing changes; the next pass tries again.</li>
 * </ul>
 * Snapshots are applied idempotently and never move an order backwards (see
 * {@link LiveOrder#applyBroker}); each order is updated in its own transaction under optimistic
 * locking, so a concurrent submit, cancel or second pass cannot overwrite a newer decision. Every
 * decision that changes an order is written to the audit trail; the user is notified when an order
 * needs them.
 */
@Service
public class OrderReconciler {

    public static final String NOT_FOUND = "NOT_FOUND_AT_BROKER";
    public static final String DUPLICATE = "DUPLICATE_AT_BROKER";
    public static final String ID_MISMATCH = "BROKER_ID_MISMATCH";

    public enum Decision {
        /** Order of unknown outcome found at the broker and linked. */
        LINKED,
        /** Broker state applied (status, fills). */
        UPDATED,
        UNCHANGED,
        /** Still SUBMITTING within the in-flight grace period: its own broker call may still answer. */
        IN_FLIGHT,
        /** Not (yet) in the broker's order book; looked for again later. */
        NOT_FOUND_YET,
        NEEDS_REVIEW,
        /** No usable connection to the broker account the order was placed with. */
        NO_CONNECTION,
        BROKER_UNAVAILABLE,
        /** Changed concurrently; the next pass decides from the newer state. */
        CONCURRENT_CHANGE
    }

    public record Result(String orderId, Decision decision, String status) {
    }

    private static final Logger log = LoggerFactory.getLogger(OrderReconciler.class);
    private static final long SYSTEM = 0L;

    private final LiveOrderRepository orders;
    private final BrokerConnectionRepository connectionRepository;
    private final BrokerConnectionService connections;
    private final CredentialVault vault;
    private final BrokerAdapter broker;
    private final LiveAuditService audit;
    private final NotificationService notifications;
    private final LiveProperties properties;
    private final TransactionTemplate tx;
    private final Clock clock;

    public OrderReconciler(LiveOrderRepository orders, BrokerConnectionRepository connectionRepository,
                           BrokerConnectionService connections, CredentialVault vault, BrokerAdapter broker,
                           LiveAuditService audit, NotificationService notifications, LiveProperties properties,
                           PlatformTransactionManager txManager, Clock clock) {
        this.orders = orders;
        this.connectionRepository = connectionRepository;
        this.connections = connections;
        this.vault = vault;
        this.broker = broker;
        this.audit = audit;
        this.notifications = notifications;
        this.properties = properties;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
    }

    /** Background pass over every user with an unresolved or working order. Does nothing without a broker. */
    @Scheduled(fixedDelayString = "${tradeflow.live.reconcile-interval:PT60S}",
            initialDelayString = "${tradeflow.live.reconcile-initial-delay:PT60S}")
    public void reconcileAll() {
        if (!broker.enabled()) {
            return;
        }
        for (Long userId : orders.usersWithStatusIn(LiveOrderStatus.RECONCILABLE)) {
            try {
                reconcileUser(userId, SYSTEM);
            } catch (RuntimeException e) {
                log.warn("Reconciliation for user {} failed ({}); retrying next pass", userId, e.getClass().getSimpleName());
            }
        }
    }

    /**
     * Reconciles one user's orders. {@code actorId} is the user for an on-demand check, 0 for the
     * background pass; on-demand checks also audit the passes that changed nothing.
     */
    public List<Result> reconcileUser(long userId, long actorId) {
        List<LiveOrder> candidates = orders.findByUserIdAndStatusIn(userId, LiveOrderStatus.RECONCILABLE);
        List<Result> results = new ArrayList<>();
        Map<UUID, List<LiveOrder>> byConnection = candidates.stream()
                .collect(Collectors.groupingBy(LiveOrder::getConnectionId));
        for (var entry : byConnection.entrySet()) {
            Optional<BrokerConnection> account = accountFor(userId, entry.getKey());
            List<BrokerAdapter.BrokerOrder> book = null;
            Decision unavailable = null;
            if (account.isEmpty()) {
                unavailable = Decision.NO_CONNECTION;
            } else {
                try {
                    book = broker.orders(vault.accessToken(account.get().getId()));
                } catch (RuntimeException e) {
                    log.warn("Broker order book unavailable for user {} ({})", userId, e.getClass().getSimpleName());
                    unavailable = Decision.BROKER_UNAVAILABLE;
                }
            }
            for (LiveOrder order : entry.getValue()) {
                Result r = unavailable != null ? new Result(order.getId(), unavailable, order.getStatus())
                        : decide(order.getId(), book);
                results.add(r);
                if (actorId != SYSTEM && !changed(r.decision())) {
                    audit.record(userId, "RECONCILE_ORDER", LiveAuditService.Outcome.INFO,
                            r.orderId() + " " + r.decision() + " " + r.status(), actorId);
                }
            }
        }
        return results;
    }

    private Result decide(String orderId, List<BrokerAdapter.BrokerOrder> book) {
        Instant now = clock.instant();
        String[] note = new String[1];
        Result result;
        try {
            result = tx.execute(s -> {
                LiveOrder o = orders.findById(orderId).orElseThrow();
                if (!LiveOrderStatus.RECONCILABLE.contains(o.getStatus())) {
                    return new Result(orderId, Decision.UNCHANGED, o.getStatus());
                }
                Duration age = Duration.between(o.getCreatedAt(), now);
                if (LiveOrderStatus.SUBMITTING.equals(o.getStatus()) && age.compareTo(properties.reconcileInFlightGrace()) < 0) {
                    return new Result(orderId, Decision.IN_FLIGHT, o.getStatus());
                }
                o.checked(now);
                Decision d = o.getBrokerOrderId() != null ? byBrokerId(o, book, now, note) : byClientOrderId(o, book, age, now, note);
                return new Result(orderId, d, o.getStatus());
            });
        } catch (ObjectOptimisticLockingFailureException e) {
            return new Result(orderId, Decision.CONCURRENT_CHANGE, null);
        }
        if (changed(result.decision())) {
            LiveOrder o = orders.findById(orderId).orElseThrow();
            audit.record(o.getUserId(), "RECONCILE_ORDER", LiveAuditService.Outcome.INFO, orderId + " "
                    + result.decision() + " " + result.status() + " broker " + o.getBrokerOrderId()
                    + (note[0] == null ? "" : " (" + note[0] + ")"), SYSTEM);
            if (result.decision() == Decision.NEEDS_REVIEW) {
                notifications.notify(o.getUserId(), "ORDER_NEEDS_REVIEW", "Live order " + orderId + " ("
                        + o.getSide() + " " + o.getQuantity() + " " + o.getSymbol() + ") needs your attention: "
                        + o.getStatusMessage() + " It was NOT resubmitted. New live orders are blocked until you "
                        + "check your broker account and mark it as resolved.");
            } else if (result.decision() == Decision.LINKED) {
                notifications.notify(o.getUserId(), "ORDER_CONFIRMED", "Live order " + orderId + " was found at "
                        + "your broker (status " + o.getStatus() + "). You can place new orders again.");
            }
        }
        return result;
    }

    /** An order the broker acknowledged: refresh its state. Absence from the book proves nothing here. */
    private Decision byBrokerId(LiveOrder o, List<BrokerAdapter.BrokerOrder> book, Instant now, String[] note) {
        List<BrokerAdapter.BrokerOrder> match = book.stream().filter(b -> o.getBrokerOrderId().equals(b.brokerOrderId())).toList();
        if (match.isEmpty()) {
            return Decision.UNCHANGED;
        }
        BrokerAdapter.BrokerOrder b = match.get(0);
        if (b.clientOrderId() != null && !b.clientOrderId().equals(o.getClientOrderId())) {
            note[0] = "broker order carries a different client order id";
            return o.needsReview(ID_MISMATCH, "The broker's record of this order does not match TradeFlow's. Check "
                    + "your broker account.", now) ? Decision.NEEDS_REVIEW : Decision.UNCHANGED;
        }
        if (!LiveOrderStatus.FROM_BROKER.contains(b.status())) {
            note[0] = "unmapped broker status";
            log.warn("Broker reported unmapped status for live order {}; ignored", o.getId());
            return Decision.UNCHANGED;
        }
        return o.applyBroker(b, now) ? Decision.UPDATED : Decision.UNCHANGED;
    }

    /** An order whose submission outcome is unknown: look it up by the tag TradeFlow sent. */
    private Decision byClientOrderId(LiveOrder o, List<BrokerAdapter.BrokerOrder> book, Duration age, Instant now,
                                     String[] note) {
        List<BrokerAdapter.BrokerOrder> match = book.stream()
                .filter(b -> o.getClientOrderId().equals(b.clientOrderId())).toList();
        if (match.size() > 1) {
            note[0] = match.size() + " broker orders carry this client order id";
            return o.needsReview(DUPLICATE, "Your broker shows " + match.size() + " orders for this one request. "
                    + "Check them in your broker account; TradeFlow did not cancel any of them.", now)
                    ? Decision.NEEDS_REVIEW : Decision.UNCHANGED;
        }
        if (match.size() == 1) {
            BrokerAdapter.BrokerOrder b = match.get(0);
            if (b.brokerOrderId() == null || !LiveOrderStatus.FROM_BROKER.contains(b.status())) {
                note[0] = "broker entry without id or with an unmapped status";
                return Decision.UNCHANGED;
            }
            o.applyBroker(b, now);
            return Decision.LINKED;
        }
        if (age.compareTo(properties.reconcileAbsenceWindow()) < 0) {
            if (LiveOrderStatus.SUBMITTING.equals(o.getStatus())) {
                o.outcomeUnknown("The submission was interrupted. TradeFlow is checking your broker's order book.", now);
                note[0] = "submission interrupted";
                return Decision.UPDATED;
            }
            return Decision.NOT_FOUND_YET;
        }
        note[0] = "absent after " + age.toMinutes() + " min, " + o.getReconcileChecks() + " checks";
        return o.needsReview(NOT_FOUND, "Your broker's order book does not show this order. It may not have been "
                + "placed, but TradeFlow cannot be sure.", now) ? Decision.NEEDS_REVIEW : Decision.UNCHANGED;
    }

    /**
     * The connection the order was placed with if still usable, otherwise the user's current one if
     * it is the same broker account (a reconnect creates a new connection). Never a different account.
     */
    private Optional<BrokerConnection> accountFor(long userId, UUID connectionId) {
        Instant now = clock.instant();
        Optional<BrokerConnection> original = connectionRepository.findById(connectionId);
        if (original.isPresent() && original.get().usable(now)) {
            return original;
        }
        String account = original.map(BrokerConnection::getBrokerAccountRef).orElse(null);
        return connections.active(userId)
                .filter(c -> account != null && account.equals(c.getBrokerAccountRef()));
    }

    private static boolean changed(Decision d) {
        return d == Decision.LINKED || d == Decision.UPDATED || d == Decision.NEEDS_REVIEW;
    }
}
