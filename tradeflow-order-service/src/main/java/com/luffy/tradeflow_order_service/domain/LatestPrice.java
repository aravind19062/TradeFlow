package com.luffy.tradeflow_order_service.domain;

import com.luffy.tradeflow.common.events.Events.OrderSide;
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

    /** Simulated touch; null for ticks recorded before the spread existed (treated as the mid). */
    private BigDecimal bid;
    private BigDecimal ask;

    /** Shares already taken from the simulated book this tick. A new tick starts a fresh book. */
    @Column(name = "ask_consumed", nullable = false)
    private long askConsumed;

    @Column(name = "bid_consumed", nullable = false)
    private long bidConsumed;

    public LatestPrice(String symbol, BigDecimal price, long tick, Instant asOf, BigDecimal bid, BigDecimal ask) {
        this.symbol = symbol;
        this.price = price;
        this.tick = tick;
        this.asOf = asOf;
        this.bid = bid;
        this.ask = ask;
    }

    /** Ignores out-of-order (older) ticks. @return true if the price changed */
    public boolean update(BigDecimal newPrice, long newTick, Instant newAsOf, BigDecimal newBid, BigDecimal newAsk) {
        if (newTick <= tick) {
            return false;
        }
        this.price = newPrice;
        this.tick = newTick;
        this.asOf = newAsOf;
        this.bid = newBid;
        this.ask = newAsk;
        this.askConsumed = 0;
        this.bidConsumed = 0;
        return true;
    }

    /** Shares taken this tick by orders on {@code takerSide}. */
    public long consumed(OrderSide takerSide) {
        return takerSide == OrderSide.BUY ? askConsumed : bidConsumed;
    }

    public void recordConsumed(OrderSide takerSide, long total) {
        if (takerSide == OrderSide.BUY) {
            askConsumed = total;
        } else {
            bidConsumed = total;
        }
    }

    public Touch touch() {
        return new Touch(bid == null ? price : bid, ask == null ? price : ask);
    }
}
