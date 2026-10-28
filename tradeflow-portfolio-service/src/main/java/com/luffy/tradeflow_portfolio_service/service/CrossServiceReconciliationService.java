package com.luffy.tradeflow_portfolio_service.service;

import com.luffy.tradeflow.common.events.Events;
import com.luffy.tradeflow.common.security.ServiceTokenProvider;
import com.luffy.tradeflow.common.web.ApiException;
import com.luffy.tradeflow_portfolio_service.client.OrderServiceClient;
import com.luffy.tradeflow_portfolio_service.client.OrderServiceClient.OrderStatusView;
import com.luffy.tradeflow_portfolio_service.domain.Reservation;
import com.luffy.tradeflow_portfolio_service.repository.ReservationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Compares portfolio reservations with order-service's view of the same orders,
 * catching inconsistencies a single database cannot see. Reservations still ACTIVE
 * after {@code staleAfter} are classified:
 * <ul>
 *   <li>LEAKED_RESERVATION: the order ended without a fill (REJECTED/CANCELLED/EXPIRED).
 *       Released automatically: no trade can consume it, so releasing is safe.</li>
 *   <li>UNSETTLED_FILL: the order is FILLED but the trade was never booked. Not
 *       released (the trade is real); order-service's saga re-drives settlement.</li>
 *   <li>ORPHAN_RESERVATION: order-service does not know the order. Needs an operator.</li>
 * </ul>
 * PENDING/OPEN orders are in progress and ignored.
 */
@Slf4j
@Service
public class CrossServiceReconciliationService {

    public record CrossServiceIssue(UUID orderId, long userId, String type, String orderStatus, String action) {
    }

    public record CrossServiceReport(Instant checkedAt, int reservationsChecked, boolean orderServiceReachable,
                                     int autoReleased, List<CrossServiceIssue> issues) {
        public boolean needsAttention() {
            return !orderServiceReachable
                    || issues.stream().anyMatch(i -> !"RELEASED_AUTOMATICALLY".equals(i.action()));
        }
    }

    private static final Set<String> ENDED_WITHOUT_FILL = Set.of("REJECTED", "CANCELLED", "EXPIRED");

    private final ReservationRepository reservationRepository;
    private final OrderServiceClient orderServiceClient;
    private final ServiceTokenProvider serviceTokens;
    private final AccountingService accountingService;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final Duration staleAfter;
    private volatile CrossServiceReport lastReport;

    public CrossServiceReconciliationService(ReservationRepository reservationRepository,
                                             OrderServiceClient orderServiceClient, ServiceTokenProvider serviceTokens,
                                             AccountingService accountingService,
                                             TransactionTemplate transactionTemplate, Clock clock,
                                             @Value("${tradeflow.portfolio.cross-check-stale-after:PT5M}")
                                             Duration staleAfter) {
        this.reservationRepository = reservationRepository;
        this.orderServiceClient = orderServiceClient;
        this.serviceTokens = serviceTokens;
        this.accountingService = accountingService;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
        this.staleAfter = staleAfter;
    }

    @Scheduled(fixedDelayString = "${tradeflow.portfolio.cross-check-interval-ms:120000}", initialDelay = 60000)
    public void scheduledCheck() {
        CrossServiceReport report = run();
        report.issues().forEach(i -> log.error("CROSS-SERVICE MISMATCH order={} user={} type={} orderStatus={} action={}",
                i.orderId(), i.userId(), i.type(), i.orderStatus(), i.action()));
    }

    public CrossServiceReport run() {
        Instant now = clock.instant();
        List<Reservation> stale = reservationRepository.findByStatusAndCreatedAtBefore(Reservation.Status.ACTIVE,
                now.minus(staleAfter));
        List<CrossServiceIssue> issues = new ArrayList<>();
        int released = 0;
        Map<UUID, OrderStatusView> statuses;
        try {
            statuses = fetch(stale.stream().map(Reservation::getOrderId).toList());
        } catch (ApiException e) {
            log.warn("Cross-service reconciliation skipped: {}", e.getMessage());
            CrossServiceReport report = new CrossServiceReport(now, stale.size(), false, 0, List.of());
            lastReport = report;
            return report;
        }
        for (Reservation r : stale) {
            OrderStatusView order = statuses.get(r.getOrderId());
            if (order == null) {
                issues.add(new CrossServiceIssue(r.getOrderId(), r.getUserId(), "ORPHAN_RESERVATION", null,
                        "NEEDS_OPERATOR"));
            } else if (ENDED_WITHOUT_FILL.contains(order.status())) {
                transactionTemplate.executeWithoutResult(tx -> accountingService.release(new Events.OrderCancelled(
                        r.getOrderId(), r.getUserId(), "reconciliation: order is " + order.status())));
                released++;
                issues.add(new CrossServiceIssue(r.getOrderId(), r.getUserId(), "LEAKED_RESERVATION", order.status(),
                        "RELEASED_AUTOMATICALLY"));
            } else if ("FILLED".equals(order.status())) {
                issues.add(new CrossServiceIssue(r.getOrderId(), r.getUserId(), "UNSETTLED_FILL", order.status(),
                        "AWAITING_SETTLEMENT_REDRIVE"));
            }
        }
        CrossServiceReport report = new CrossServiceReport(now, stale.size(), true, released, issues);
        lastReport = report;
        return report;
    }

    public CrossServiceReport lastReport() {
        return lastReport;
    }

    private Map<UUID, OrderStatusView> fetch(List<UUID> ids) {
        Map<UUID, OrderStatusView> result = new java.util.HashMap<>();
        for (int i = 0; i < ids.size(); i += 200) {
            result.putAll(orderServiceClient.statuses(ids.subList(i, Math.min(ids.size(), i + 200)),
                            serviceTokens.token()).stream()
                    .collect(Collectors.toMap(OrderStatusView::orderId, Function.identity())));
        }
        return result;
    }
}
