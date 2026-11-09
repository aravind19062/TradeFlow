package com.luffy.tradeflow_order_service.domain;

import com.luffy.tradeflow.common.events.Events.OrderSide;
import com.luffy.tradeflow.common.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** One execution; an order can have several. Only {@code settledAt} changes after it is written. */
@Entity
@Table(name = "trades")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Trade {

    @Id
    private UUID id;

    @Column(name = "order_id", nullable = false)
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

    @Column(nullable = false)
    private BigDecimal price;

    @Column(name = "gross_amount", nullable = false)
    private BigDecimal grossAmount;

    @Column(name = "executed_at", nullable = false)
    private Instant executedAt;

    /** When portfolio-service confirmed it booked this trade. */
    @Column(name = "settled_at")
    private Instant settledAt;

    public void markSettled(Instant now) {
        if (settledAt == null) {
            settledAt = now;
        }
    }

    public static Trade execute(Order order, long quantity, BigDecimal price, Instant executedAt) {
        Trade trade = new Trade();
        trade.id = UUID.randomUUID();
        trade.orderId = order.getId();
        trade.userId = order.getUserId();
        trade.symbol = order.getSymbol();
        trade.side = order.getSide();
        trade.quantity = quantity;
        trade.price = Money.price(price);
        trade.grossAmount = Money.amount(quantity, trade.price);
        trade.executedAt = executedAt;
        return trade;
    }
}
