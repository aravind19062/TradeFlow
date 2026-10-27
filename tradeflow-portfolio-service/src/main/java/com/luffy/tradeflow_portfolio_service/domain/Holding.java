package com.luffy.tradeflow_portfolio_service.domain;

import com.luffy.tradeflow.common.kafka.InconsistentStateException;
import com.luffy.tradeflow.common.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Position in one symbol. Average cost uses the weighted-average method:
 * buys blend into the average, sells realize (price − average cost) × quantity
 * and leave the average unchanged. Rows are kept at quantity 0 so realized P&L
 * history survives a full exit.
 */
@Entity
@Table(name = "holdings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Holding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private long userId;

    @Column(nullable = false)
    private String symbol;

    @Column(nullable = false)
    private long quantity;

    /** Shares earmarked for open SELL orders. */
    @Column(name = "reserved_quantity", nullable = false)
    private long reservedQuantity;

    @Column(name = "average_cost", nullable = false)
    private BigDecimal averageCost;

    @Column(name = "realized_pnl", nullable = false)
    private BigDecimal realizedPnl;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    public Holding(long userId, String symbol, Instant now) {
        this.userId = userId;
        this.symbol = symbol;
        this.averageCost = Money.cost(BigDecimal.ZERO);
        this.realizedPnl = Money.cash(BigDecimal.ZERO);
        this.updatedAt = now;
    }

    public long availableQuantity() {
        return quantity - reservedQuantity;
    }

    public void reserve(long qty, Instant now) {
        if (qty > availableQuantity()) {
            throw new IllegalStateException("Insufficient available quantity");
        }
        reservedQuantity += qty;
        updatedAt = now;
    }

    public void release(long qty, Instant now) {
        if (qty > reservedQuantity) {
            throw new InconsistentStateException("Holding " + symbol + " for user " + userId + " would release "
                    + qty + " shares but only " + reservedQuantity + " are reserved");
        }
        reservedQuantity -= qty;
        updatedAt = now;
    }

    /** new average = (oldQty × oldAvg + cost) / (oldQty + qty), at 4 decimals. */
    public void applyBuy(long qty, BigDecimal cost, Instant now) {
        BigDecimal totalCost = averageCost.multiply(BigDecimal.valueOf(quantity)).add(cost);
        quantity += qty;
        averageCost = totalCost.divide(BigDecimal.valueOf(quantity), Money.COST_SCALE, Money.ROUNDING);
        updatedAt = now;
    }

    /** Sells previously reserved shares; returns realized P&L at cash scale. */
    public BigDecimal applySell(long qty, BigDecimal price, Instant now) {
        release(qty, now);
        if (qty > quantity) {
            throw new InconsistentStateException("Cannot sell " + qty + " " + symbol + "; holding " + quantity);
        }
        BigDecimal realized = Money.cash(price.subtract(averageCost).multiply(BigDecimal.valueOf(qty)));
        quantity -= qty;
        realizedPnl = realizedPnl.add(realized);
        if (quantity == 0) {
            averageCost = Money.cost(BigDecimal.ZERO);
        }
        return realized;
    }

    public BigDecimal costBasis() {
        return Money.cash(averageCost.multiply(BigDecimal.valueOf(quantity)));
    }
}
