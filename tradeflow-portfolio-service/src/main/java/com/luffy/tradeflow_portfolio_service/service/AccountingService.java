package com.luffy.tradeflow_portfolio_service.service;

import com.luffy.tradeflow.common.events.Events;
import com.luffy.tradeflow.common.events.Events.OrderSide;
import com.luffy.tradeflow.common.events.Topics;
import com.luffy.tradeflow.common.kafka.InconsistentStateException;
import com.luffy.tradeflow.common.money.Money;
import com.luffy.tradeflow.common.outbox.OutboxWriter;
import com.luffy.tradeflow_portfolio_service.config.PortfolioProperties;
import com.luffy.tradeflow_portfolio_service.domain.Account;
import com.luffy.tradeflow_portfolio_service.domain.Holding;
import com.luffy.tradeflow_portfolio_service.domain.LedgerEntry;
import com.luffy.tradeflow_portfolio_service.domain.OrderTombstone;
import com.luffy.tradeflow_portfolio_service.domain.Reservation;
import com.luffy.tradeflow_portfolio_service.repository.AccountRepository;
import com.luffy.tradeflow_portfolio_service.repository.HoldingRepository;
import com.luffy.tradeflow_portfolio_service.repository.LedgerEntryRepository;
import com.luffy.tradeflow_portfolio_service.repository.ReservationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;

/**
 * The portfolio side of the order saga. Every method runs inside the Kafka
 * listener's transaction (MANDATORY), together with the idempotency marker and
 * any reply event in the outbox.
 *
 * <p>Money moves in two steps: {@link #reserve} earmarks cash/shares when an order
 * is placed, {@link #settle} books the actual fill against that reservation, and
 * {@link #release} returns it if the order is cancelled. A trade that does not
 * match an active reservation is an inconsistency: it is rejected to the DLT and
 * logged, never booked.
 */
@Slf4j
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class AccountingService {

    private final AccountRepository accountRepository;
    private final HoldingRepository holdingRepository;
    private final ReservationRepository reservationRepository;
    private final LedgerEntryRepository ledgerRepository;
    private final com.luffy.tradeflow_portfolio_service.repository.OrderTombstoneRepository tombstoneRepository;
    private final OutboxWriter outboxWriter;
    private final PortfolioProperties properties;
    private final Clock clock;

    public AccountingService(AccountRepository accountRepository, HoldingRepository holdingRepository,
                             ReservationRepository reservationRepository, LedgerEntryRepository ledgerRepository,
                             com.luffy.tradeflow_portfolio_service.repository.OrderTombstoneRepository tombstoneRepository,
                             OutboxWriter outboxWriter, PortfolioProperties properties, Clock clock) {
        this.accountRepository = accountRepository;
        this.holdingRepository = holdingRepository;
        this.reservationRepository = reservationRepository;
        this.ledgerRepository = ledgerRepository;
        this.tombstoneRepository = tombstoneRepository;
        this.outboxWriter = outboxWriter;
        this.properties = properties;
        this.clock = clock;
    }

    public void openAccount(Events.UserRegistered event) {
        if (accountRepository.existsById(event.userId())) {
            log.info("Account for user {} already exists", event.userId());
            return;
        }
        Instant now = clock.instant();
        Account account = accountRepository.save(Account.open(event.userId(), properties.initialCash(), now));
        ledgerRepository.save(LedgerEntry.deposit(event.userId(), account.getCashBalance(),
                account.getCashBalance(), now));
        log.info("Opened account for user {} with simulated cash {}", event.userId(), account.getCashBalance());
    }

    public void reserve(Events.OrderPlaced event) {
        // Lock the account first: a concurrent release for the same order (other topic, other
        // thread) then runs strictly before or after this, never interleaved.
        Account account = accountRepository.findForUpdate(event.userId()).orElse(null);
        if (reservationRepository.existsById(event.orderId())) {
            log.info("Order {} already has a reservation", event.orderId());
            return;
        }
        if (tombstoneRepository.existsById(event.orderId())) {
            reject(event, "ORDER_CANCELLED", "The order was cancelled or timed out before funds were reserved");
            return;
        }
        if (account == null) {
            reject(event, "ACCOUNT_NOT_FOUND", "No cash account exists for this user yet");
            return;
        }
        Instant now = clock.instant();
        if (event.side() == OrderSide.BUY) {
            BigDecimal amount = Money.amount(event.quantity(), event.reservationPrice());
            if (!account.canReserve(amount)) {
                reject(event, "INSUFFICIENT_FUNDS", "Order requires " + amount + " but only "
                        + Money.cash(account.availableCash()) + " is available");
                return;
            }
            account.reserve(amount, now);
            reservationRepository.save(new Reservation(event.orderId(), event.userId(), event.symbol(),
                    OrderSide.BUY, event.quantity(), amount, now));
            outboxWriter.append(Topics.ORDERS_RESERVED, event.orderId().toString(),
                    new Events.OrderReserved(event.orderId(), event.userId(), amount, 0));
        } else {
            Holding holding = holdingRepository.findByUserIdAndSymbol(event.userId(), event.symbol()).orElse(null);
            long available = holding == null ? 0 : holding.availableQuantity();
            if (available < event.quantity()) {
                reject(event, "INSUFFICIENT_POSITION", "Order sells " + event.quantity() + " " + event.symbol()
                        + " but only " + available + " are available");
                return;
            }
            holding.reserve(event.quantity(), now);
            reservationRepository.save(new Reservation(event.orderId(), event.userId(), event.symbol(),
                    OrderSide.SELL, event.quantity(), Money.cash(BigDecimal.ZERO), now));
            outboxWriter.append(Topics.ORDERS_RESERVED, event.orderId().toString(),
                    new Events.OrderReserved(event.orderId(), event.userId(), Money.cash(BigDecimal.ZERO),
                            event.quantity()));
        }
        log.info("Reserved for order {} ({} {} x{})", event.orderId(), event.side(), event.symbol(),
                event.quantity());
    }

    public void settle(Events.TradeExecuted event) {
        // Lock first, then check the ledger: two concurrent deliveries of the same trade
        // are serialized here, and the second one sees the first as booked.
        Reservation reservation = reservationRepository.findForUpdate(event.orderId()).orElse(null);
        if (ledgerRepository.existsByTradeId(event.tradeId())) {
            // Redelivery or re-drive: confirm again so the order saga can complete.
            log.info("Trade {} is already booked; re-confirming settlement", event.tradeId());
            confirmSettled(event);
            return;
        }
        if (reservation == null) {
            throw new InconsistentStateException("Trade " + event.tradeId() + " refers to order " + event.orderId()
                    + " which has no reservation");
        }
        verifyMatches(reservation, event);
        Account account = accountRepository.findById(event.userId())
                .orElseThrow(() -> new InconsistentStateException("No account for user " + event.userId()));
        Instant now = clock.instant();
        BigDecimal gross = event.grossAmount();
        if (event.side() == OrderSide.BUY) {
            account.settleBuy(gross, reservation.getCashAmount(), now);
            Holding holding = holdingRepository.findByUserIdAndSymbol(event.userId(), event.symbol())
                    .orElseGet(() -> new Holding(event.userId(), event.symbol(), now));
            holding.applyBuy(event.quantity(), gross, now);
            holdingRepository.save(holding);
            ledgerRepository.save(LedgerEntry.trade(event.userId(), LedgerEntry.Type.BUY, event.symbol(),
                    event.quantity(), event.price(), gross.negate(), account.getCashBalance(), null,
                    event.tradeId(), event.orderId(), event.executedAt()));
        } else {
            Holding holding = holdingRepository.findByUserIdAndSymbol(event.userId(), event.symbol())
                    .orElseThrow(() -> new InconsistentStateException("SELL trade " + event.tradeId()
                            + " but user " + event.userId() + " holds no " + event.symbol()));
            BigDecimal realized = holding.applySell(event.quantity(), event.price(), now);
            account.settleSell(gross, realized, now);
            ledgerRepository.save(LedgerEntry.trade(event.userId(), LedgerEntry.Type.SELL, event.symbol(),
                    event.quantity(), event.price(), gross, account.getCashBalance(), realized,
                    event.tradeId(), event.orderId(), event.executedAt()));
        }
        reservation.settle(now);
        confirmSettled(event);
        log.info("Settled trade {} ({} {} x{} @ {})", event.tradeId(), event.side(), event.symbol(),
                event.quantity(), event.price());
    }

    public void release(Events.OrderCancelled event) {
        accountRepository.findForUpdate(event.userId()); // same lock as reserve(): no interleaving
        Reservation reservation = reservationRepository.findById(event.orderId()).orElse(null);
        if (reservation == null) {
            if (!tombstoneRepository.existsById(event.orderId())) {
                tombstoneRepository.save(new OrderTombstone(event.orderId(), event.reason(), clock.instant()));
            }
            log.info("Nothing to release for order {}; tombstone recorded", event.orderId());
            replyReleased(event, Events.ReleaseOutcome.NOTHING_TO_RELEASE);
            return;
        }
        if (reservation.getStatus() == Reservation.Status.SETTLED) {
            // Never undo an executed trade: report it instead of releasing.
            log.error("Release requested for order {} whose reservation is already SETTLED", event.orderId());
            replyReleased(event, Events.ReleaseOutcome.ALREADY_SETTLED);
            return;
        }
        if (!reservation.isActive()) {
            log.info("Reservation for order {} already released", event.orderId());
            replyReleased(event, Events.ReleaseOutcome.NOTHING_TO_RELEASE);
            return;
        }
        if (reservation.getUserId() != event.userId()) {
            throw new InconsistentStateException("Cancel for order " + event.orderId() + " from wrong user");
        }
        Instant now = clock.instant();
        if (reservation.getSide() == OrderSide.BUY) {
            accountRepository.findById(event.userId())
                    .orElseThrow(() -> new InconsistentStateException("No account for user " + event.userId()))
                    .release(reservation.getCashAmount(), now);
        } else {
            holdingRepository.findByUserIdAndSymbol(event.userId(), reservation.getSymbol())
                    .orElseThrow(() -> new InconsistentStateException("No holding to release for order "
                            + event.orderId()))
                    .release(reservation.getQuantity(), now);
        }
        reservation.release(now);
        replyReleased(event, Events.ReleaseOutcome.RELEASED);
        log.info("Released reservation for order {} ({})", event.orderId(), event.reason());
    }

    private void confirmSettled(Events.TradeExecuted event) {
        outboxWriter.append(Topics.TRADES_SETTLED, event.orderId().toString(),
                new Events.TradeSettled(event.tradeId(), event.orderId(), event.userId()));
    }

    private void replyReleased(Events.OrderCancelled event, Events.ReleaseOutcome outcome) {
        outboxWriter.append(Topics.ORDERS_RELEASED, event.orderId().toString(),
                new Events.OrderReleased(event.orderId(), event.userId(), outcome));
    }

    private void reject(Events.OrderPlaced event, String code, String reason) {
        outboxWriter.append(Topics.ORDERS_REJECTED, event.orderId().toString(),
                new Events.OrderRejected(event.orderId(), event.userId(), code, reason));
        log.info("Rejected order {}: {} ({})", event.orderId(), code, reason);
    }

    private static void verifyMatches(Reservation r, Events.TradeExecuted e) {
        String mismatch = null;
        if (!r.isActive()) {
            mismatch = "reservation is " + r.getStatus();
        } else if (r.getUserId() != e.userId()) {
            mismatch = "user differs";
        } else if (!r.getSymbol().equals(e.symbol()) || r.getSide() != e.side()) {
            mismatch = "symbol/side differs";
        } else if (r.getQuantity() != e.quantity()) {
            mismatch = "quantity " + e.quantity() + " != reserved " + r.getQuantity();
        } else if (Money.amount(e.quantity(), e.price()).compareTo(e.grossAmount()) != 0) {
            mismatch = "grossAmount " + e.grossAmount() + " != quantity × price";
        }
        if (mismatch != null) {
            throw new InconsistentStateException("Trade " + e.tradeId() + " does not match reservation for order "
                    + e.orderId() + ": " + mismatch);
        }
    }
}
