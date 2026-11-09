package com.luffy.tradeflow_portfolio_service.domain;

import com.luffy.tradeflow.common.events.Events.OrderSide;
import com.luffy.tradeflow.common.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

/**
 * Funds (BUY) or shares (SELL) held for one order. {@code quantity} and {@code cashAmount} are what
 * was originally held. Each trade settles a slice, and a cancel or expiry releases what is left.
 * It stays ACTIVE until every share is accounted for.
 */
@Entity
@Table(name = "reservations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Reservation {

    public enum Status { ACTIVE, SETTLED, RELEASED }

    @Id
    @Column(name = "order_id")
    private UUID orderId;

    @Column(name = "user_id", nullable = false)
    private long userId;

    @Column(nullable = false)
    private String symbol;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderSide side;

    @Column(nullable = false)
    private long quantity;

    @Column(name = "cash_amount", nullable = false)
    private BigDecimal cashAmount;

    /** Cash still held: {@code cashAmount} less what settled trades and releases have freed. */
    @Column(name = "remaining_cash", nullable = false)
    private BigDecimal remainingCash;

    @Column(name = "settled_quantity", nullable = false)
    private long settledQuantity;

    @Column(name = "released_quantity", nullable = false)
    private long releasedQuantity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Version
    private long version;

    public Reservation(UUID orderId, long userId, String symbol, OrderSide side, long quantity,
                       BigDecimal cashAmount, Instant now) {
        this.orderId = orderId;
        this.userId = userId;
        this.symbol = symbol;
        this.side = side;
        this.quantity = quantity;
        this.cashAmount = cashAmount;
        this.remainingCash = cashAmount;
        this.status = Status.ACTIVE;
        this.createdAt = now;
    }

    public boolean isActive() {
        return status == Status.ACTIVE;
    }

    public long remainingQuantity() {
        return quantity - settledQuantity - releasedQuantity;
    }

    /**
     * Books one trade against this reservation.
     *
     * @param cost what the trade costs (zero for a SELL)
     * @return the cash this trade stops holding: its share of the reservation, of which the part above
     *         {@code cost} is price improvement going back to the user
     */
    public BigDecimal settle(long tradedQuantity, BigDecimal cost, Instant now) {
        assertActive();
        BigDecimal stillNeeded = cashFor(remainingQuantity() - tradedQuantity);
        BigDecimal freed = remainingCash.subtract(stillNeeded).max(cost.min(remainingCash));
        remainingCash = remainingCash.subtract(freed);
        settledQuantity += tradedQuantity;
        closeIfDone(now);
        return freed;
    }

    /** What a release gave back. */
    public record Released(long quantity, BigDecimal cash) {
    }

    /**
     * Releases everything except what executed trades still need, including trades that portfolio
     * hasn't received yet.
     *
     * @param executedQuantity total quantity order-service has executed for this order
     */
    public Released release(long executedQuantity, Instant now) {
        assertActive();
        long inFlight = Math.max(0, executedQuantity - settledQuantity);
        long releasable = Math.max(0, remainingQuantity() - inFlight);
        if (releasable == 0) {
            return new Released(0, BigDecimal.ZERO);
        }
        BigDecimal stillNeeded = cashFor(remainingQuantity() - releasable);
        BigDecimal freed = remainingCash.subtract(stillNeeded);
        remainingCash = stillNeeded;
        releasedQuantity += releasable;
        closeIfDone(now);
        return new Released(releasable, freed);
    }

    /** Always computed from the original amounts, so rounding cannot accumulate across fills. */
    private BigDecimal cashFor(long shares) {
        return cashAmount.multiply(BigDecimal.valueOf(shares))
                .divide(BigDecimal.valueOf(quantity), Money.CASH_SCALE, RoundingMode.UP);
    }

    private void closeIfDone(Instant now) {
        if (remainingQuantity() == 0) {
            status = releasedQuantity > 0 ? Status.RELEASED : Status.SETTLED;
            resolvedAt = now;
        }
    }

    private void assertActive() {
        if (status != Status.ACTIVE) {
            throw new IllegalStateException("Reservation " + orderId + " is already " + status);
        }
    }
}
