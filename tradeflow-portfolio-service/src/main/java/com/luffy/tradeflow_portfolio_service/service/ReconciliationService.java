package com.luffy.tradeflow_portfolio_service.service;

import com.luffy.tradeflow.common.events.Events.OrderSide;
import com.luffy.tradeflow_portfolio_service.domain.Account;
import com.luffy.tradeflow_portfolio_service.domain.Holding;
import com.luffy.tradeflow_portfolio_service.domain.LedgerEntry;
import com.luffy.tradeflow_portfolio_service.domain.Reservation;
import com.luffy.tradeflow_portfolio_service.repository.AccountRepository;
import com.luffy.tradeflow_portfolio_service.repository.HoldingRepository;
import com.luffy.tradeflow_portfolio_service.repository.LedgerEntryRepository;
import com.luffy.tradeflow_portfolio_service.repository.ReservationRepository;
import com.luffy.tradeflow_portfolio_service.web.PortfolioViews.ReconciliationIssue;
import com.luffy.tradeflow_portfolio_service.web.PortfolioViews.ReconciliationReport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Independently re-derives balances and compares them with stored state:
 * <ol>
 *   <li>reserved cash == sum of ACTIVE BUY reservations</li>
 *   <li>reserved shares == sum of ACTIVE SELL reservations, per symbol</li>
 *   <li>cash balance == sum of ledger cash deltas == last ledger balance</li>
 *   <li>share quantity == sum of ledger share deltas, per symbol</li>
 *   <li>account realized P&L == sum of holding realized P&L</li>
 * </ol>
 * Runs every minute; any mismatch is logged at ERROR and turns the
 * {@code accounting} health indicator DOWN.
 *
 * <p>Each check reads accounts, holdings, reservations and the ledger in separate
 * queries, so it runs in one REPEATABLE READ (snapshot) transaction: a trade that
 * settles mid-check is either fully visible or not at all, which prevents false
 * mismatch alarms.
 */
@Slf4j
@Service
public class ReconciliationService {

    private final AccountRepository accountRepository;
    private final HoldingRepository holdingRepository;
    private final ReservationRepository reservationRepository;
    private final LedgerEntryRepository ledgerRepository;
    private final Clock clock;
    private volatile ReconciliationReport lastReport;

    public ReconciliationService(AccountRepository accountRepository, HoldingRepository holdingRepository,
                                 ReservationRepository reservationRepository, LedgerEntryRepository ledgerRepository,
                                 Clock clock) {
        this.accountRepository = accountRepository;
        this.holdingRepository = holdingRepository;
        this.reservationRepository = reservationRepository;
        this.ledgerRepository = ledgerRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ReconciliationReport check(long userId) {
        List<ReconciliationIssue> issues = new ArrayList<>();
        var account = accountRepository.findById(userId);
        account.ifPresent(a -> issues.addAll(checkAccount(a)));
        return new ReconciliationReport(clock.instant(), account.isPresent() ? 1 : 0, issues.isEmpty(), issues);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ReconciliationReport checkAll() {
        List<Account> accounts = accountRepository.findAll();
        List<ReconciliationIssue> issues = new ArrayList<>();
        for (Account account : accounts) {
            issues.addAll(checkAccount(account));
        }
        ReconciliationReport report = new ReconciliationReport(clock.instant(), accounts.size(), issues.isEmpty(),
                issues);
        lastReport = report;
        return report;
    }

    // Annotated here as well: the call to checkAll() below is a self-invocation that
    // bypasses the transactional proxy, so the scheduled run needs its own snapshot.
    @Scheduled(fixedDelayString = "${tradeflow.portfolio.reconciliation-interval-ms:60000}", initialDelay = 30000)
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public void scheduledCheck() {
        ReconciliationReport report = checkAll();
        report.issues().forEach(i -> log.error("RECONCILIATION MISMATCH user={} check={} expected={} actual={}",
                i.userId(), i.check(), i.expected(), i.actual()));
    }

    public ReconciliationReport lastReport() {
        return lastReport;
    }

    List<ReconciliationIssue> checkAccount(Account account) {
        long userId = account.getUserId();
        List<ReconciliationIssue> issues = new ArrayList<>();
        List<Reservation> active = reservationRepository.findByUserIdAndStatus(userId, Reservation.Status.ACTIVE);
        List<Holding> holdings = holdingRepository.findByUserIdOrderBySymbolAsc(userId);
        List<LedgerEntry> ledger = ledgerRepository.findByUserIdOrderByOccurredAtAscIdAsc(userId);

        BigDecimal reservedCash = active.stream().filter(r -> r.getSide() == OrderSide.BUY)
                .map(Reservation::getCashAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        compare(issues, userId, "reserved_cash", reservedCash, account.getReservedCash());

        Map<String, Long> reservedShares = new HashMap<>();
        active.stream().filter(r -> r.getSide() == OrderSide.SELL)
                .forEach(r -> reservedShares.merge(r.getSymbol(), r.getQuantity(), Long::sum));
        Map<String, Long> ledgerShares = new HashMap<>();
        BigDecimal ledgerCash = BigDecimal.ZERO;
        for (LedgerEntry e : ledger) {
            ledgerCash = ledgerCash.add(e.getCashDelta());
            if (e.getSymbol() != null) {
                ledgerShares.merge(e.getSymbol(), e.quantityDelta(), Long::sum);
            }
        }
        compare(issues, userId, "cash_balance_vs_ledger_sum", ledgerCash, account.getCashBalance());
        if (!ledger.isEmpty()) {
            compare(issues, userId, "cash_balance_vs_last_ledger_entry",
                    ledger.get(ledger.size() - 1).getCashBalanceAfter(), account.getCashBalance());
        }
        BigDecimal holdingRealized = BigDecimal.ZERO;
        for (Holding h : holdings) {
            holdingRealized = holdingRealized.add(h.getRealizedPnl());
            long expectedReserved = reservedShares.getOrDefault(h.getSymbol(), 0L);
            if (expectedReserved != h.getReservedQuantity()) {
                issues.add(new ReconciliationIssue(userId, "reserved_quantity:" + h.getSymbol(),
                        String.valueOf(expectedReserved), String.valueOf(h.getReservedQuantity())));
            }
            long expectedQty = ledgerShares.getOrDefault(h.getSymbol(), 0L);
            if (expectedQty != h.getQuantity()) {
                issues.add(new ReconciliationIssue(userId, "quantity_vs_ledger:" + h.getSymbol(),
                        String.valueOf(expectedQty), String.valueOf(h.getQuantity())));
            }
        }
        compare(issues, userId, "realized_pnl", holdingRealized, account.getRealizedPnl());
        return issues;
    }

    private static void compare(List<ReconciliationIssue> issues, long userId, String check, BigDecimal expected,
                                BigDecimal actual) {
        if (expected.compareTo(actual) != 0) {
            issues.add(new ReconciliationIssue(userId, check, expected.toPlainString(), actual.toPlainString()));
        }
    }

    @Component("accounting")
    static class AccountingHealthIndicator extends AbstractHealthIndicator {

        private final ReconciliationService service;

        AccountingHealthIndicator(ReconciliationService service) {
            this.service = service;
        }

        @Override
        protected void doHealthCheck(Health.Builder builder) {
            ReconciliationReport report = service.lastReport();
            if (report == null) {
                builder.up().withDetail("lastCheck", "not run yet");
                return;
            }
            builder.withDetail("lastCheck", report.checkedAt().toString())
                    .withDetail("accountsChecked", report.accountsChecked())
                    .withDetail("issues", report.issues().size());
            if (report.consistent()) {
                builder.up();
            } else {
                builder.down();
            }
        }
    }
}
