package com.luffy.tradeflow_order_service.saga;

import com.luffy.tradeflow_order_service.domain.Order;
import com.luffy.tradeflow_order_service.domain.OrderStatus;
import com.luffy.tradeflow_order_service.repository.OrderRepository;
import com.luffy.tradeflow_order_service.repository.TradeRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.List;

/**
 * Timeouts and recovery for sagas whose current step is overdue. Each saga is handled
 * in its own transaction; if a reply arrives concurrently, the optimistic lock makes
 * one side lose and the saga is re-evaluated on the next scan.
 *
 * <ul>
 *   <li>AWAITING_RESERVATION: portfolio never answered. Reject the order and request a
 *       release (covers a reservation that was made but whose reply was lost).</li>
 *   <li>RESTING: the LIMIT order outlived its time-to-live. Expire it and release.</li>
 *   <li>AWAITING_SETTLEMENT: the trade executed but was never confirmed settled. Re-send
 *       the same trade (portfolio de-duplicates by trade id). Funds are never released:
 *       the trade happened. After the re-drive budget, mark SETTLEMENT_FAILED.</li>
 *   <li>RELEASE_REQUESTED: no confirmation. Re-send the release, then mark RELEASE_FAILED.</li>
 * </ul>
 */
@Slf4j
@Component
public class SagaRecoveryJob {

    private final OrderSagaRepository sagaRepository;
    private final OrderRepository orderRepository;
    private final TradeRepository tradeRepository;
    private final OrderSagaService sagaService;
    private final SagaProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public SagaRecoveryJob(OrderSagaRepository sagaRepository, OrderRepository orderRepository,
                           TradeRepository tradeRepository, OrderSagaService sagaService, SagaProperties properties,
                           TransactionTemplate transactionTemplate, Clock clock) {
        this.sagaRepository = sagaRepository;
        this.orderRepository = orderRepository;
        this.tradeRepository = tradeRepository;
        this.sagaService = sagaService;
        this.properties = properties;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${tradeflow.saga.scan-interval-ms:10000}", initialDelay = 5000)
    public void recoverOverdue() {
        List<OrderSaga> overdue = sagaRepository.findByStateInAndDeadlineAtBeforeOrderByDeadlineAtAsc(
                SagaState.IN_FLIGHT, clock.instant(), Limit.of(100));
        for (OrderSaga candidate : overdue) {
            try {
                transactionTemplate.executeWithoutResult(tx -> recover(candidate.getOrderId()));
            } catch (OptimisticLockingFailureException e) {
                log.debug("Saga {} changed concurrently; will re-check next scan", candidate.getOrderId());
            } catch (RuntimeException e) {
                log.warn("Recovery of saga {} failed: {}", candidate.getOrderId(), e.getMessage());
            }
        }
    }

    /** Package-visible so tests can drive a single recovery step deterministically. */
    void recover(java.util.UUID orderId) {
        OrderSaga saga = sagaRepository.findById(orderId).orElse(null);
        if (saga == null || !saga.getState().isInFlight() || saga.getDeadlineAt() == null
                || saga.getDeadlineAt().isAfter(clock.instant())) {
            return;
        }
        Order order = orderRepository.findById(orderId).orElse(null);
        if (order == null) {
            sagaService.fail(saga, SagaState.RELEASE_FAILED, "saga without order");
            return;
        }
        switch (saga.getState()) {
            case AWAITING_RESERVATION -> {
                if (order.getStatus() == OrderStatus.PENDING) {
                    order.reject("RESERVATION_TIMEOUT", "No funds/position answer from portfolio-service within "
                            + properties.reservationTimeout(), clock.instant());
                }
                sagaService.requestRelease(order, "reservation timeout");
                log.warn("Order {} rejected after reservation timeout; release requested", orderId);
            }
            case RESTING -> {
                if (order.getStatus() == OrderStatus.OPEN) {
                    order.expire("LIMIT order expired after " + properties.limitOrderTtl(), clock.instant());
                    sagaService.requestRelease(order, "limit order expired");
                    log.info("LIMIT order {} expired; release requested", orderId);
                }
            }
            case AWAITING_SETTLEMENT -> {
                if (saga.getRedriveCount() >= properties.maxRedrives()) {
                    sagaService.fail(saga, SagaState.SETTLEMENT_FAILED, "no trades.settled after "
                            + saga.getRedriveCount() + " re-drives");
                    return;
                }
                tradeRepository.findByOrderIdOrderByExecutedAtAsc(orderId).stream().findFirst().ifPresentOrElse(
                        trade -> {
                            sagaService.redriveSettlement(saga, trade);
                            log.warn("Re-driving settlement of trade {} (attempt {})", trade.getId(),
                                    saga.getRedriveCount());
                        },
                        () -> sagaService.fail(saga, SagaState.SETTLEMENT_FAILED, "FILLED order has no trade"));
            }
            case RELEASE_REQUESTED -> {
                if (saga.getRedriveCount() >= properties.maxRedrives()) {
                    sagaService.fail(saga, SagaState.RELEASE_FAILED, "no orders.released after "
                            + saga.getRedriveCount() + " re-sends");
                    return;
                }
                sagaService.requestRelease(order, "release re-sent (attempt " + (saga.getRedriveCount() + 1) + ")");
            }
            default -> {
            }
        }
    }
}
