package com.luffy.tradeflow.common.events;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Version 1 payloads for every TradeFlow event. Grouped in one file so the whole
 * contract can be reviewed at a glance; see docs/EVENTS.md for semantics.
 */
public final class Events {

    private Events() {
    }

    public enum OrderSide { BUY, SELL }

    public enum OrderType { MARKET, LIMIT }

    /** users.registered (user-service → portfolio-service): open a cash account. */
    public record UserRegistered(
            @NotNull Long userId,
            @NotBlank String email,
            @NotBlank String name) {
    }

    /**
     * orders.placed (order-service → portfolio-service): reserve funds or shares.
     * {@code reservationPrice} is the per-share price used to size a BUY cash
     * reservation: the accepted quote for MARKET orders, the limit for LIMIT orders.
     */
    public record OrderPlaced(
            @NotNull UUID orderId,
            @NotNull Long userId,
            @NotBlank String symbol,
            @NotNull OrderSide side,
            @NotNull OrderType type,
            @Positive long quantity,
            @NotNull @Positive BigDecimal reservationPrice) {
    }

    /** orders.reserved (portfolio-service → order-service): funds/shares are held. */
    public record OrderReserved(
            @NotNull UUID orderId,
            @NotNull Long userId,
            @NotNull @PositiveOrZero BigDecimal reservedCash,
            @PositiveOrZero long reservedQuantity) {
    }

    /** orders.rejected (portfolio-service → order-service): reservation refused. */
    public record OrderRejected(
            @NotNull UUID orderId,
            @NotNull Long userId,
            @NotBlank String reasonCode,
            @NotBlank String reason) {
    }

    /** orders.cancelled (order-service → portfolio-service): release any reservation. */
    public record OrderCancelled(
            @NotNull UUID orderId,
            @NotNull Long userId,
            @NotBlank String reason) {
    }

    /** trades.executed (order-service → portfolio-service): settle the fill. */
    public record TradeExecuted(
            @NotNull UUID tradeId,
            @NotNull UUID orderId,
            @NotNull Long userId,
            @NotBlank String symbol,
            @NotNull OrderSide side,
            @Positive long quantity,
            @NotNull @Positive BigDecimal price,
            @NotNull @Positive BigDecimal grossAmount,
            @NotNull Instant executedAt) {
    }

    /**
     * trades.settled (portfolio-service → order-service): the trade is booked (cash,
     * holdings, ledger). Re-sent when an already-booked trade is redelivered, so the
     * order saga can complete even if the first confirmation was lost.
     */
    public record TradeSettled(
            @NotNull UUID tradeId,
            @NotNull UUID orderId,
            @NotNull Long userId) {
    }

    /** Outcome of a release request. */
    public enum ReleaseOutcome {
        /** An ACTIVE reservation was released. */
        RELEASED,
        /** No reservation existed; a tombstone now blocks a late one. Also the reply to repeated requests. */
        NOTHING_TO_RELEASE,
        /** The reservation was already consumed by a trade, so nothing was released. */
        ALREADY_SETTLED
    }

    /** orders.released (portfolio-service → order-service): reply to orders.cancelled. */
    public record OrderReleased(
            @NotNull UUID orderId,
            @NotNull Long userId,
            @NotNull ReleaseOutcome outcome) {
    }

    /** prices.updated (market-data-service → order-service): a new simulated tick. */
    public record PriceUpdated(
            @NotBlank String symbol,
            @NotNull @Positive BigDecimal price,
            @PositiveOrZero long tick,
            @NotNull Instant asOf,
            @NotBlank String source) {
    }
}
