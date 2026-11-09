package com.luffy.tradeflow_live_trading_service.broker;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * The only way live-trading-service talks to a broker. One implementation per approved broker,
 * using the broker's OFFICIAL authorisation flow (redirect + code; TradeFlow never sees the user's
 * broker password or one-time codes). Until a broker is approved, only {@link DisabledBrokerAdapter}
 * exists in production code.
 */
public interface BrokerAdapter {

    /** Short stable code, e.g. "none" or the broker's name. */
    String code();

    boolean enabled();

    /**
     * Whether TradeFlow may place and cancel real orders through this broker. False while an
     * integration is read-only (phase 6): previews and orders are then refused before any limit
     * check or broker call.
     */
    default boolean ordersEnabled() {
        return enabled();
    }

    /** The broker's login/consent URL for this user; {@code state} protects against CSRF. */
    String authorizationUrl(String state, String codeChallenge);

    BrokerSession exchangeCode(String code, String codeVerifier);

    void revoke(String accessToken);

    Funds funds(String accessToken);

    List<Holding> holdings(String accessToken);

    List<Position> positions(String accessToken);

    /**
     * The broker's order book (at least today's orders). Each order must carry the
     * {@code clientOrderId} TradeFlow sent with it (the broker's order tag), and a status mapped onto
     * {@code LiveOrderStatus.FROM_BROKER}. Reconciliation relies on both.
     */
    List<BrokerOrder> orders(String accessToken);

    BrokerOrderAck placeOrder(String accessToken, OrderRequest request);

    BrokerOrderAck cancelOrder(String accessToken, String brokerOrderId);

    List<Quote> quotes(String accessToken, List<String> instruments);

    record BrokerSession(String accessToken, String refreshToken, Instant expiresAt, String accountRef) {
        @Override
        public String toString() {
            return "BrokerSession[accountRef=" + accountRef + ", expiresAt=" + expiresAt + ", tokens=<redacted>]";
        }
    }

    record Funds(BigDecimal available, BigDecimal used, String currency, Instant asOf) {
    }

    record Holding(String exchange, String symbol, long quantity, BigDecimal averagePrice, BigDecimal lastPrice) {
    }

    record Position(String exchange, String symbol, long quantity, BigDecimal averagePrice, BigDecimal pnl) {
    }

    /** {@code updatedAt} is the broker's own timestamp for this state (used to discard stale snapshots). */
    record BrokerOrder(String brokerOrderId, String clientOrderId, String exchange, String symbol, String side, String type,
                       long quantity, long filledQuantity, BigDecimal price, BigDecimal averagePrice, String status,
                       String message, Instant updatedAt) {
    }

    record OrderRequest(String clientOrderId, String exchange, String symbol, String side, String type, long quantity,
                        BigDecimal limitPrice, BigDecimal triggerPrice) {
    }

    record BrokerOrderAck(String brokerOrderId, String status, String message) {
    }

    /** {@code source} is the broker; never SIMULATED. */
    record Quote(String exchange, String symbol, BigDecimal lastPrice, BigDecimal bid, BigDecimal ask, Instant asOf,
                 String source) {
    }
}
