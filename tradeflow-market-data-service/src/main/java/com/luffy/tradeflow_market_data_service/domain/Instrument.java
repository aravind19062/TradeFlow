package com.luffy.tradeflow_market_data_service.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Entity
@Table(name = "instruments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class Instrument {

    @Id
    private String symbol;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String sector;

    @Column(name = "asset_class", nullable = false)
    private String assetClass;

    @Column(nullable = false)
    private String currency;

    /** Price at tick 0 of the simulation. */
    @Column(name = "base_price", nullable = false)
    private BigDecimal basePrice;

    /** Standard deviation of the per-tick return. */
    @Column(nullable = false)
    private BigDecimal volatility;

    /** Mean per-tick return. */
    @Column(nullable = false)
    private BigDecimal drift;
}
