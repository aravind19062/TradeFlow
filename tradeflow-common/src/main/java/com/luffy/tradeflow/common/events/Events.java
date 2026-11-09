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

    /**
     * MARKET and LIMIT for both sides. STOP (stop-market) and STOP_LIMIT are SELL-only in the demo
     * (it is long-only): they rest untriggered until the bid falls to the trigger price.
     */
    public enum OrderType { MARKET, LIMIT, STOP, STOP_LIMIT }

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

    /**
     * orders.cancelled (order-service → portfolio-service): release what the order no longer needs.
     * {@code filledQuantity} is what has executed so far (absent on older events, meaning none): those
     * shares' funds stay held even if their trades.executed has not reached portfolio yet.
     */
    public record OrderCancelled(
            @NotNull UUID orderId,
            @NotNull Long userId,
            @NotBlank String reason,
            @PositiveOrZero Long filledQuantity) {

        /** A release for an order with no executions. */
        public OrderCancelled(UUID orderId, Long userId, String reason) {
            this(orderId, userId, reason, null);
        }

        public long executedQuantity() {
            return filledQuantity == null ? 0 : filledQuantity;
        }
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

    /**
     * prices.updated (market-data-service → order-service): a new simulated tick. {@code price} is the
     * mid; {@code bid}/{@code ask} are the simulated touch (absent on events from before the spread
     * existed, which consumers treat as bid = ask = price).
     */
    public record PriceUpdated(
            @NotBlank String symbol,
            @NotNull @Positive BigDecimal price,
            @PositiveOrZero long tick,
            @NotNull Instant asOf,
            @NotBlank String source,
            @Positive BigDecimal bid,
            @Positive BigDecimal ask) {

        /** A tick without a simulated spread (bid = ask = price). */
        public PriceUpdated(String symbol, BigDecimal price, long tick, Instant asOf, String source) {
            this(symbol, price, tick, asOf, source, null, null);
        }

        public BigDecimal bidOrMid() {
            return bid == null ? price : bid;
        }

        public BigDecimal askOrMid() {
            return ask == null ? price : ask;
        }
    }

    /**
     * readiness.changed (learning-service → live-trading-service): the user's readiness state
     * moved. Consumers treat it as a hint and confirm eligibility synchronously before any live
     * action (the event alone never grants access).
     */
    public record ReadinessChanged(
            @NotNull Long userId,
            @NotBlank String fromState,
            @NotBlank String toState,
            @NotBlank String reasonCode,
            boolean qualified,
            int policyVersion,
            @NotNull Instant occurredAt) {
    }
}
