package com.luffy.tradeflow_portfolio_service.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Immutable journal line. Replaying the ledger reproduces cash and positions at any point in time. */
@Entity
@Table(name = "ledger_entries")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LedgerEntry {

    public enum Type { DEPOSIT, BUY, SELL }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false)
    private Type type;

    private String symbol;

    private Long quantity;

    private BigDecimal price;

    /** Signed change to cash: negative for buys, positive for deposits and sells. */
    @Column(name = "cash_delta", nullable = false)
    private BigDecimal cashDelta;

    @Column(name = "cash_balance_after", nullable = false)
    private BigDecimal cashBalanceAfter;

    @Column(name = "realized_pnl")
    private BigDecimal realizedPnl;

    @Column(name = "trade_id", unique = true)
    private UUID tradeId;

    @Column(name = "order_id")
    private UUID orderId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    public static LedgerEntry deposit(long userId, BigDecimal amount, BigDecimal balanceAfter, Instant at) {
        LedgerEntry e = new LedgerEntry();
        e.userId = userId;
        e.type = Type.DEPOSIT;
        e.cashDelta = amount;
        e.cashBalanceAfter = balanceAfter;
        e.occurredAt = at;
        return e;
    }

    public static LedgerEntry trade(long userId, Type type, String symbol, long quantity, BigDecimal price,
                                    BigDecimal cashDelta, BigDecimal balanceAfter, BigDecimal realizedPnl,
                                    UUID tradeId, UUID orderId, Instant at) {
        LedgerEntry e = new LedgerEntry();
        e.userId = userId;
        e.type = type;
        e.symbol = symbol;
        e.quantity = quantity;
        e.price = price;
        e.cashDelta = cashDelta;
        e.cashBalanceAfter = balanceAfter;
        e.realizedPnl = realizedPnl;
        e.tradeId = tradeId;
        e.orderId = orderId;
        e.occurredAt = at;
        return e;
    }

    /** Signed share change: + for BUY, − for SELL, 0 otherwise. */
    public long quantityDelta() {
        if (quantity == null) {
            return 0;
        }
        return type == Type.SELL ? -quantity : (type == Type.BUY ? quantity : 0);
    }
}
