package com.luffy.tradeflow_order_service.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "latest_prices")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LatestPrice {

    @Id
    private String symbol;

    @Column(nullable = false)
    private BigDecimal price;

    @Column(nullable = false)
    private long tick;

    @Column(name = "as_of", nullable = false)
    private Instant asOf;

    public LatestPrice(String symbol, BigDecimal price, long tick, Instant asOf) {
        this.symbol = symbol;
        this.price = price;
        this.tick = tick;
        this.asOf = asOf;
    }

    /** Ignores out-of-order (older) ticks. @return true if the price changed */
    public boolean update(BigDecimal newPrice, long newTick, Instant newAsOf) {
        if (newTick <= tick) {
            return false;
        }
        this.price = newPrice;
        this.tick = newTick;
        this.asOf = newAsOf;
        return true;
    }
}
