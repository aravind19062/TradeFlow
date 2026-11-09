package com.luffy.tradeflow_order_service.saga;

import com.luffy.tradeflow.common.events.Events.OrderType;
import com.luffy.tradeflow_order_service.domain.Order;
import com.luffy.tradeflow_order_service.domain.OrderStatus;
import com.luffy.tradeflow_order_service.domain.Trade;
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
 *   <li>RESTING: the working order outlived its time-to-live. Expire whatever is unfilled and
 *       release it; trades that already happened stand.</li>
 *   <li>AWAITING_SETTLEMENT: trades executed but were never confirmed settled. Re-send them
 *       (portfolio de-duplicates by trade id). Their funds are never released: the trades
 *       happened. After the re-drive budget, mark SETTLEMENT_FAILED.</li>
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

    private void expire(Order order) {
        String filled = order.getFilledQuantity() == 0 ? ""
                : " with " + order.getFilledQuantity() + " of " + order.getQuantity() + " filled";
        if (order.getType() == OrderType.MARKET) {
            order.expireForLiquidity("Not enough liquidity inside the price protection within "
                    + properties.marketOrderTtl() + filled + "; the unfilled part was released", clock.instant());
        } else {
            order.expire(order.getType() + " order expired after " + properties.limitOrderTtl() + filled,
                    clock.instant());
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
                if (order.getStatus().isWorking()) {
                    expire(order);
                    sagaService.requestRelease(order, order.getType() + " order expired");
                    log.info("{} order {} expired with {} of {} filled; release requested", order.getType(), orderId,
                            order.getFilledQuantity(), order.getQuantity());
                }
            }
            case AWAITING_SETTLEMENT -> {
                if (saga.getRedriveCount() >= properties.maxRedrives()) {
                    sagaService.fail(saga, SagaState.SETTLEMENT_FAILED, "no trades.settled after "
                            + saga.getRedriveCount() + " re-drives");
                    return;
                }
                List<Trade> trades = tradeRepository.findByOrderIdOrderByExecutedAtAsc(orderId);
                List<Trade> unconfirmed = trades.stream().filter(t -> t.getSettledAt() == null).toList();
                if (trades.isEmpty()) {
                    sagaService.fail(saga, SagaState.SETTLEMENT_FAILED, "FILLED order has no trade");
                } else if (unconfirmed.isEmpty()) {
                    sagaService.finishSettlement(saga);
                } else {
                    sagaService.redriveSettlement(saga, unconfirmed);
                    log.warn("Re-driving settlement of {} trade(s) of order {} (attempt {})", unconfirmed.size(),
                            orderId, saga.getRedriveCount());
                }
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
