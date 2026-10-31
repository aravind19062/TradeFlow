package com.luffy.tradeflow_live_trading_service.orders;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** A real-money order sent to the broker. Ids start with "L-" so a demo order id can never match one. */
@Entity
@Table(name = "live_orders")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LiveOrder {

    @Id
    private String id;
    @Column(name = "user_id", nullable = false)
    private long userId;
    @Column(name = "connection_id", nullable = false)
    private UUID connectionId;
    @Column(name = "client_order_id", nullable = false)
    private String clientOrderId;
    @Column(name = "broker_order_id")
    private String brokerOrderId;
    @Column(nullable = false)
    private String exchange;
    @Column(nullable = false)
    private String symbol;
    @Column(nullable = false)
    private String side;
    @Column(name = "order_type", nullable = false)
    private String orderType;
    @Column(nullable = false)
    private long quantity;
    @Column(name = "limit_price")
    private BigDecimal limitPrice;
    @Column(name = "trigger_price")
    private BigDecimal triggerPrice;
    @Column(name = "order_value", nullable = false)
    private BigDecimal orderValue;
    @Column(nullable = false)
    private String status;
    @Column(name = "status_message")
    private String statusMessage;
    @Column(name = "filled_quantity", nullable = false)
    private long filledQuantity;
    @Column(name = "average_price")
    private BigDecimal averagePrice;
    @Column(name = "confirmation_id", nullable = false)
    private UUID confirmationId;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Version
    private long version;

    LiveOrder(long userId, UUID connectionId, String clientOrderId, OrderParams p, BigDecimal orderValue,
              UUID confirmationId, Instant now) {
        this.id = "L-" + UUID.randomUUID();
        this.userId = userId;
        this.connectionId = connectionId;
        this.clientOrderId = clientOrderId;
        this.exchange = p.exchange();
        this.symbol = p.symbol();
        this.side = p.side();
        this.orderType = p.type();
        this.quantity = p.quantity();
        this.limitPrice = p.limitPrice();
        this.triggerPrice = p.triggerPrice();
        this.orderValue = orderValue;
        this.status = "SUBMITTING";
        this.confirmationId = confirmationId;
        this.createdAt = now;
        this.updatedAt = now;
    }

    void acknowledged(String brokerOrderId, String status, String message, Instant now) {
        this.brokerOrderId = brokerOrderId;
        this.status = status;
        this.statusMessage = message;
        this.updatedAt = now;
    }

    /** {@code status} is REJECTED (the broker definitely refused) or UNKNOWN (outcome not known). */
    void failed(String status, String message, Instant now) {
        this.status = status;
        this.statusMessage = message;
        this.updatedAt = now;
    }

    void cancelRequested(String message, Instant now) {
        this.status = "CANCEL_REQUESTED";
        this.statusMessage = message;
        this.updatedAt = now;
    }

    public OrderParams params() {
        return new OrderParams(exchange, symbol, side, orderType, quantity, limitPrice, triggerPrice);
    }
}
