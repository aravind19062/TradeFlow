package com.luffy.tradeflow_order_service.web;

import com.luffy.tradeflow.common.events.Events.OrderSide;
import com.luffy.tradeflow.common.events.Events.OrderType;
import com.luffy.tradeflow_order_service.domain.Order;
import com.luffy.tradeflow_order_service.domain.OrderStatus;
import com.luffy.tradeflow_order_service.domain.OrderStatusChange;
import com.luffy.tradeflow_order_service.domain.Trade;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class OrderDtos {

    private OrderDtos() {
    }

    /**
     * @param clientOrderId optional idempotency key; resubmitting the same key returns the original order
     */
    public record PlaceOrderRequest(
            @NotBlank @Size(max = 20) @Pattern(regexp = "^[A-Za-z0-9.&-]+$") String symbol,
            @NotNull OrderSide side,
            @NotNull OrderType type,
            @NotNull @Positive Long quantity,
            @DecimalMin("0.01") @Digits(integer = 15, fraction = 2) BigDecimal limitPrice,
            @Size(max = 64) @Pattern(regexp = "^[A-Za-z0-9_-]+$") String clientOrderId) {
    }

    public record OrderResponse(UUID id, String clientOrderId, String symbol, OrderSide side, OrderType type,
                                long quantity, BigDecimal limitPrice, BigDecimal reservationPrice,
                                OrderStatus status, String rejectCode, String statusReason, long filledQuantity,
                                BigDecimal averageFillPrice, Instant createdAt, Instant updatedAt) {
        public static OrderResponse from(Order o) {
            return new OrderResponse(o.getId(), o.getClientOrderId(), o.getSymbol(), o.getSide(), o.getType(),
                    o.getQuantity(), o.getLimitPrice(), o.getReservationPrice(), o.getStatus(), o.getRejectCode(),
                    o.getStatusReason(), o.getFilledQuantity(), o.getAverageFillPrice(), o.getCreatedAt(),
                    o.getUpdatedAt());
        }
    }

    public record StatusChangeResponse(OrderStatus from, OrderStatus to, String reason, Instant at) {
        public static StatusChangeResponse from(OrderStatusChange c) {
            return new StatusChangeResponse(c.getFromStatus(), c.getToStatus(), c.getReason(), c.getOccurredAt());
        }
    }

    public record TradeResponse(UUID id, UUID orderId, String symbol, OrderSide side, long quantity,
                                BigDecimal price, BigDecimal grossAmount, Instant executedAt) {
        public static TradeResponse from(Trade t) {
            return new TradeResponse(t.getId(), t.getOrderId(), t.getSymbol(), t.getSide(), t.getQuantity(),
                    t.getPrice(), t.getGrossAmount(), t.getExecutedAt());
        }
    }

    public record OrderDetailResponse(OrderResponse order, List<StatusChangeResponse> history,
                                      List<TradeResponse> trades) {
    }
}
