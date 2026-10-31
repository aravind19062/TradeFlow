package com.luffy.tradeflow_learning_service.readiness;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The position-sizing exercise: account value, risk budget, entry and stop are given; the user
 * computes the quantity, then places a DEMO buy of exactly that quantity. Outcome (profit or loss)
 * is irrelevant.
 */
@Entity
@Table(name = "position_sizing_scenarios")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PositionSizingScenario {

    @Id
    private UUID id;
    @Column(name = "user_id", nullable = false)
    private long userId;
    @Column(nullable = false)
    private String symbol;
    @Column(name = "account_value", nullable = false)
    private BigDecimal accountValue;
    @Column(name = "risk_bps", nullable = false)
    private int riskBps;
    @Column(name = "entry_price", nullable = false)
    private BigDecimal entryPrice;
    @Column(name = "stop_price", nullable = false)
    private BigDecimal stopPrice;
    @Column(name = "expected_quantity", nullable = false)
    private long expectedQuantity;
    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
    @Column(name = "answered_correctly_at")
    private Instant answeredCorrectlyAt;
    @Column(name = "answer_attempts", nullable = false)
    private int answerAttempts;

    PositionSizingScenario(long userId, String symbol, BigDecimal accountValue, int riskBps, BigDecimal entryPrice,
                           BigDecimal stopPrice, Instant now, Instant expiresAt) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.symbol = symbol;
        this.accountValue = accountValue;
        this.riskBps = riskBps;
        this.entryPrice = entryPrice;
        this.stopPrice = stopPrice;
        this.expectedQuantity = quantityFor(accountValue, riskBps, entryPrice, stopPrice);
        this.issuedAt = now;
        this.expiresAt = expiresAt;
    }

    /** floor(account × risk / (entry − stop)). */
    static long quantityFor(BigDecimal account, int riskBps, BigDecimal entry, BigDecimal stop) {
        BigDecimal atRisk = account.multiply(BigDecimal.valueOf(riskBps)).divide(BigDecimal.valueOf(10_000));
        return atRisk.divide(entry.subtract(stop), 0, java.math.RoundingMode.FLOOR).longValueExact();
    }

    boolean answer(long quantity, Instant now) {
        answerAttempts++;
        if (quantity == expectedQuantity && answeredCorrectlyAt == null) {
            answeredCorrectlyAt = now;
        }
        return quantity == expectedQuantity;
    }

    boolean active(Instant now) {
        return now.isBefore(expiresAt);
    }
}
