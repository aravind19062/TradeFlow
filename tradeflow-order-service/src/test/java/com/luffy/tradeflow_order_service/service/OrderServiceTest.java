package com.luffy.tradeflow_order_service.service;

import com.luffy.tradeflow.common.events.Events.OrderSide;
import com.luffy.tradeflow.common.events.Events.OrderType;
import com.luffy.tradeflow.common.outbox.OutboxWriter;
import com.luffy.tradeflow.common.security.AuthenticatedUser;
import com.luffy.tradeflow.common.web.ApiException;
import com.luffy.tradeflow_order_service.client.MarketDataClient;
import com.luffy.tradeflow_order_service.config.OrderProperties;
import com.luffy.tradeflow_order_service.domain.Order;
import com.luffy.tradeflow_order_service.repository.OrderRepository;
import com.luffy.tradeflow_order_service.repository.TradeRepository;
import com.luffy.tradeflow_order_service.web.OrderDtos.PlaceOrderRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderServiceTest {

    private final OrderRepository orders = mock(OrderRepository.class);
    private final MarketDataClient marketData = mock(MarketDataClient.class);
    private final OutboxWriter outbox = mock(OutboxWriter.class);
    private final TransactionTemplate tx = mock(TransactionTemplate.class);
    private final AuthenticatedUser user = new AuthenticatedUser(7, "u@test.dev", Set.of("USER"), "t");
    private OrderService service;

    @BeforeEach
    void setUp() {
        OrderProperties props = new OrderProperties(1_000L, new BigDecimal("100000"), null);
        service = new OrderService(orders, mock(TradeRepository.class), marketData, outbox, props, tx,
                mock(com.luffy.tradeflow_order_service.saga.OrderSagaService.class), Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
        when(tx.execute(any())).thenAnswer(inv -> inv.<TransactionCallback<?>>getArgument(0).doInTransaction(null));
        when(marketData.quote(anyString())).thenReturn(
                new MarketDataClient.Quote("TCS", new BigDecimal("100.00"), 5, Instant.now(), "SIMULATED"));
    }

    private static PlaceOrderRequest request(OrderType type, long qty, String limit, String clientId) {
        return new PlaceOrderRequest("tcs", OrderSide.BUY, type, qty, limit == null ? null : new BigDecimal(limit),
                clientId);
    }

    @Test
    void marketOrderReservesAtQuoteAndWritesOutboxEvent() {
        var result = service.place(user, request(OrderType.MARKET, 10, null, null));
        assertThat(result.created()).isTrue();
        assertThat(result.order().symbol()).isEqualTo("TCS");
        assertThat(result.order().reservationPrice()).isEqualByComparingTo("100.00");
        verify(orders).save(any(Order.class));
        verify(outbox).append(eq("orders.placed"), eq("7"), any());
    }

    @Test
    void limitOrderRequiresLimitPrice() {
        assertThatThrownBy(() -> service.place(user, request(OrderType.LIMIT, 10, null, null)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("LIMIT_PRICE_REQUIRED"));
    }

    @Test
    void marketOrderMustNotHaveLimitPrice() {
        assertThatThrownBy(() -> service.place(user, request(OrderType.MARKET, 10, "99.00", null)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("LIMIT_PRICE_NOT_ALLOWED"));
    }

    @Test
    void rejectsQuantityAboveLimitBeforeCallingMarketData() {
        assertThatThrownBy(() -> service.place(user, request(OrderType.MARKET, 5_000, null, null)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("QUANTITY_TOO_LARGE"));
        verify(marketData, never()).quote(anyString());
    }

    @Test
    void rejectsOrderValueAboveLimit() {
        // 1000 x 100.00 = 100000.00 is allowed; 1000 x 100.01 is not.
        assertThat(service.place(user, request(OrderType.LIMIT, 1_000, "100.00", null)).created()).isTrue();
        assertThatThrownBy(() -> service.place(user, request(OrderType.LIMIT, 1_000, "100.01", null)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("ORDER_TOO_LARGE"));
    }

    @Test
    void sameClientOrderIdReturnsOriginalOrder() {
        Order existing = Order.place(7, "abc", "TCS", OrderSide.BUY, OrderType.MARKET, 10, null,
                new BigDecimal("100.00"), Instant.now());
        when(orders.findByUserIdAndClientOrderId(7, "abc")).thenReturn(Optional.of(existing));

        var result = service.place(user, request(OrderType.MARKET, 10, null, "abc"));

        assertThat(result.created()).isFalse();
        assertThat(result.order().id()).isEqualTo(existing.getId());
        verify(outbox, never()).append(anyString(), anyString(), any());
    }

    @Test
    void reusedClientOrderIdWithDifferentParametersIsAConflict() {
        Order existing = Order.place(7, "abc", "TCS", OrderSide.BUY, OrderType.MARKET, 10, null,
                new BigDecimal("100.00"), Instant.now());
        when(orders.findByUserIdAndClientOrderId(7, "abc")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.place(user, request(OrderType.MARKET, 11, null, "abc")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void marketDataOutageSurfacesAs503() {
        when(marketData.quote(anyString()))
                .thenThrow(ApiException.dependencyUnavailable("market-data-service", "connection refused"));
        assertThatThrownBy(() -> service.place(user, request(OrderType.MARKET, 10, null, null)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(503));
        verify(orders, never()).save(any());
    }
}
