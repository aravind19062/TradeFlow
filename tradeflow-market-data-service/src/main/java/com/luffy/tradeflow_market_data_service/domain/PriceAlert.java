package com.luffy.tradeflow_market_data_service.domain;

import com.luffy.tradeflow.common.web.ApiException;
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
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "price_alerts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PriceAlert {

    public enum Direction { ABOVE, BELOW }

    public enum Status { ACTIVE, TRIGGERED, CANCELLED }

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private long userId;

    @Column(nullable = false)
    private String symbol;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Direction direction;

    @Column(nullable = false)
    private BigDecimal threshold;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    private String note;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "triggered_at")
    private Instant triggeredAt;

    @Column(name = "triggered_price")
    private BigDecimal triggeredPrice;

    @Version
    private long version;

    public PriceAlert(long userId, String symbol, Direction direction, BigDecimal threshold, String note,
                      Instant createdAt) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.symbol = symbol;
        this.direction = direction;
        this.threshold = threshold;
        this.note = note;
        this.status = Status.ACTIVE;
        this.createdAt = createdAt;
    }

    public boolean isMetBy(BigDecimal price) {
        return direction == Direction.ABOVE ? price.compareTo(threshold) >= 0 : price.compareTo(threshold) <= 0;
    }

    public void trigger(BigDecimal price, Instant at) {
        if (status != Status.ACTIVE) {
            throw new IllegalStateException("Only active alerts can trigger");
        }
        this.status = Status.TRIGGERED;
        this.triggeredPrice = price;
        this.triggeredAt = at;
    }

    public void cancel() {
        if (status != Status.ACTIVE) {
            throw ApiException.conflict("INVALID_STATE_TRANSITION", "Only ACTIVE alerts can be cancelled; this one is "
                    + status);
        }
        this.status = Status.CANCELLED;
    }
}
