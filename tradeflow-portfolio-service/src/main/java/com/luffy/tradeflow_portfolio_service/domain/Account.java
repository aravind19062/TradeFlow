package com.luffy.tradeflow_portfolio_service.domain;

import com.luffy.tradeflow.common.kafka.InconsistentStateException;
import com.luffy.tradeflow.common.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/** A user's cash account. {@code reservedCash} is cash earmarked for open BUY orders. */
@Entity
@Table(name = "accounts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Account {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "cash_balance", nullable = false)
    private BigDecimal cashBalance;

    @Column(name = "reserved_cash", nullable = false)
    private BigDecimal reservedCash;

    @Column(name = "net_deposits", nullable = false)
    private BigDecimal netDeposits;

    @Column(name = "realized_pnl", nullable = false)
    private BigDecimal realizedPnl;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    public static Account open(long userId, BigDecimal initialCash, Instant now) {
        Account account = new Account();
        account.userId = userId;
        account.cashBalance = Money.cash(initialCash);
        account.reservedCash = Money.cash(BigDecimal.ZERO);
        account.netDeposits = Money.cash(initialCash);
        account.realizedPnl = Money.cash(BigDecimal.ZERO);
        account.createdAt = now;
        account.updatedAt = now;
        return account;
    }

    public BigDecimal availableCash() {
        return cashBalance.subtract(reservedCash);
    }

    public boolean canReserve(BigDecimal amount) {
        return availableCash().compareTo(amount) >= 0;
    }

    public void reserve(BigDecimal amount, Instant now) {
        if (!canReserve(amount)) {
            throw new IllegalStateException("Insufficient available cash");
        }
        reservedCash = reservedCash.add(amount);
        updatedAt = now;
    }

    public void release(BigDecimal amount, Instant now) {
        if (reservedCash.compareTo(amount) < 0) {
            throw new InconsistentStateException("Account " + userId + " would release " + amount
                    + " but only " + reservedCash + " is reserved");
        }
        reservedCash = reservedCash.subtract(amount);
        updatedAt = now;
    }

    /** Settles a BUY: frees the whole reservation and pays the actual cost (≤ reservation). */
    public void settleBuy(BigDecimal cost, BigDecimal reservedAmount, Instant now) {
        if (cost.compareTo(reservedAmount) > 0) {
            throw new InconsistentStateException("Trade cost " + cost + " exceeds reservation " + reservedAmount
                    + " for account " + userId);
        }
        release(reservedAmount, now);
        cashBalance = cashBalance.subtract(cost);
    }

    public void settleSell(BigDecimal proceeds, BigDecimal realized, Instant now) {
        cashBalance = cashBalance.add(proceeds);
        realizedPnl = realizedPnl.add(realized);
        updatedAt = now;
    }
}
