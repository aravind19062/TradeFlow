package com.luffy.tradeflow_market_data_service.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "price_ticks")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PriceTick {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String symbol;

    @Column(nullable = false)
    private long tick;

    @Column(nullable = false)
    private BigDecimal price;

    @Column(name = "as_of", nullable = false)
    private Instant asOf;

    public PriceTick(String symbol, long tick, BigDecimal price, Instant asOf) {
        this.symbol = symbol;
        this.tick = tick;
        this.price = price;
        this.asOf = asOf;
    }
}
