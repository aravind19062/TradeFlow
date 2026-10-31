package com.luffy.tradeflow_live_trading_service.risk;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/** A user's own limits; always clamped to the platform ceilings when applied. */
@Entity
@Table(name = "risk_limits")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RiskLimit {

    @Id
    @Column(name = "user_id")
    private long userId;
    @Column(name = "max_order_value", nullable = false)
    private BigDecimal maxOrderValue;
    @Column(name = "max_orders_per_day", nullable = false)
    private int maxOrdersPerDay;
    @Column(name = "max_daily_turnover", nullable = false)
    private BigDecimal maxDailyTurnover;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    RiskLimit(long userId, BigDecimal maxOrderValue, int maxOrdersPerDay, BigDecimal maxDailyTurnover, Instant now) {
        this.userId = userId;
        set(maxOrderValue, maxOrdersPerDay, maxDailyTurnover, now);
    }

    void set(BigDecimal maxOrderValue, int maxOrdersPerDay, BigDecimal maxDailyTurnover, Instant now) {
        this.maxOrderValue = maxOrderValue;
        this.maxOrdersPerDay = maxOrdersPerDay;
        this.maxDailyTurnover = maxDailyTurnover;
        this.updatedAt = now;
    }
}
