package com.luffy.tradeflow_live_trading_service;

import com.luffy.tradeflow.common.web.ApiException;
import com.luffy.tradeflow_live_trading_service.broker.BrokerAdapter;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** TEST ONLY. Never on the production classpath: production has only DisabledBrokerAdapter. */
public class FakeBrokerAdapter implements BrokerAdapter {

    /** TIMEOUT: nothing reached the broker. TIMEOUT_AFTER_PLACING: the broker took the order but the answer was lost. */
    public enum Mode { ACCEPT, REJECT, TIMEOUT, TIMEOUT_AFTER_PLACING }

    public static final String ACCESS_TOKEN_PREFIX = "fake-broker-access-token-";
    public final List<OrderRequest> placed = new CopyOnWriteArrayList<>();
    public final List<String> cancelled = new CopyOnWriteArrayList<>();
    public final List<String> tokensSeen = new CopyOnWriteArrayList<>();
    public volatile Mode mode = Mode.ACCEPT;
    /** The broker's order book, as {@link #orders} returns it. Tests may edit it to simulate the broker. */
    public final List<BrokerOrder> book = new CopyOnWriteArrayList<>();
    public volatile boolean bookUnavailable;
    private final AtomicInteger ids = new AtomicInteger();

    @Override public String code() { return "fake"; }
    @Override public boolean enabled() { return true; }

    @Override
    public String authorizationUrl(String state, String codeChallenge) {
        return "https://broker.example/authorize?state=" + state + "&code_challenge=" + codeChallenge;
    }

    @Override
    public BrokerSession exchangeCode(String code, String codeVerifier) {
        if (!"good-code".equals(code) || codeVerifier == null || codeVerifier.length() < 43) {
            throw ApiException.badRequest("BROKER_AUTH_FAILED", "Broker refused the code");
        }
        return new BrokerSession(ACCESS_TOKEN_PREFIX + ids.incrementAndGet(), "fake-refresh-token",
                Instant.now().plusSeconds(3600), "ACC-1");
    }

    @Override public void revoke(String accessToken, String accountRef) { tokensSeen.add(accessToken); }
    @Override public Funds funds(String t) { tokensSeen.add(t); return new Funds(new BigDecimal("50000"), BigDecimal.ZERO, "INR", Instant.now()); }
    @Override public List<Holding> holdings(String t) { tokensSeen.add(t); return List.of(); }
    @Override public List<Position> positions(String t) { tokensSeen.add(t); return List.of(); }
    @Override
    public List<BrokerOrder> orders(String t) {
        tokensSeen.add(t);
        if (bookUnavailable) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "BROKER_UNAVAILABLE", "order book down");
        }
        return List.copyOf(book);
    }

    public BrokerOrder bookEntry(String brokerId, OrderRequest r, String status, long filled, Instant at) {
        return new BrokerOrder(brokerId, r.clientOrderId(), r.exchange(), r.symbol(), r.side(), r.type(), r.quantity(),
                filled, r.limitPrice(), filled > 0 ? r.limitPrice() : null, status, status.toLowerCase(), at);
    }

    @Override
    public BrokerOrderAck placeOrder(String accessToken, OrderRequest request) {
        tokensSeen.add(accessToken);
        return switch (mode) {
            case ACCEPT -> {
                placed.add(request);
                String id = "B-" + ids.incrementAndGet();
                book.add(bookEntry(id, request, "OPEN", 0, Instant.now()));
                yield new BrokerOrderAck(id, "OPEN", "accepted");
            }
            case REJECT -> throw ApiException.unprocessable("BROKER_REJECTED", "Insufficient funds at broker");
            case TIMEOUT -> throw new ApiException(HttpStatus.GATEWAY_TIMEOUT, "BROKER_TIMEOUT", "timed out");
            case TIMEOUT_AFTER_PLACING -> {
                placed.add(request);
                book.add(bookEntry("B-" + ids.incrementAndGet(), request, "OPEN", 0, Instant.now()));
                throw new ApiException(HttpStatus.GATEWAY_TIMEOUT, "BROKER_TIMEOUT", "timed out");
            }
        };
    }

    @Override
    public BrokerOrderAck cancelOrder(String accessToken, String brokerOrderId) {
        tokensSeen.add(accessToken);
        cancelled.add(brokerOrderId);
        return new BrokerOrderAck(brokerOrderId, "CANCEL_REQUESTED", "cancel requested");
    }

    @Override
    public List<Quote> quotes(String accessToken, List<String> instruments) {
        return instruments.stream().map(i -> new Quote(i.split(":")[0], i.split(":")[1], new BigDecimal("100.00"),
                new BigDecimal("99.90"), new BigDecimal("100.10"), Instant.now(), "fake")).toList();
    }
}
