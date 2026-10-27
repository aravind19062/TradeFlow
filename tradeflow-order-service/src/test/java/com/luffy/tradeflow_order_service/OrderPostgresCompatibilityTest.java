package com.luffy.tradeflow_order_service;

import com.luffy.tradeflow.common.testsupport.PostgresTestDatabase;
import org.junit.jupiter.api.Tag;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import com.luffy.tradeflow.common.events.EventCodec;
import com.luffy.tradeflow.common.events.Events;
import com.luffy.tradeflow.common.events.Topics;
import com.luffy.tradeflow.common.security.AuthenticatedUser;
import com.luffy.tradeflow_order_service.client.MarketDataClient;
import com.luffy.tradeflow_order_service.domain.OrderStatus;
import com.luffy.tradeflow_order_service.messaging.OrderEventListener;
import com.luffy.tradeflow_order_service.service.OrderService;
import com.luffy.tradeflow_order_service.web.OrderDtos.PlaceOrderRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/** Order placement, idempotency constraint, execution and filtered paging on real PostgreSQL 16. */
@Tag("postgres")
@SpringBootTest
@ActiveProfiles("test")
class OrderPostgresCompatibilityTest {

    private static final String DATABASE = "tradeflow_order_it";

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        PostgresTestDatabase db = PostgresTestDatabase.get();
        registry.add("spring.datasource.url", () -> db.createDatabase(DATABASE));
        registry.add("spring.datasource.username", db::username);
        registry.add("spring.datasource.password", db::password);
    }

    @Autowired OrderService orderService;
    @Autowired OrderEventListener listener;
    @Autowired EventCodec codec;
    @MockitoBean MarketDataClient marketData;

    @Test
    void placesAndExecutesOrdersOnPostgres() {
        when(marketData.quote(anyString())).thenReturn(
                new MarketDataClient.Quote("TCS", new BigDecimal("123.45"), 1, Instant.now(), "SIMULATED"));
        var user = new AuthenticatedUser(5, "pg@test.dev", Set.of("USER"), "t");
        var request = new PlaceOrderRequest("TCS", Events.OrderSide.BUY, Events.OrderType.MARKET, 3L, null, "pg-1");

        var placed = orderService.place(user, request);
        assertThat(orderService.place(user, request).created()).isFalse(); // unique (user_id, client_order_id)

        listener.onReserved(codec.encode(codec.wrap(Topics.ORDERS_RESERVED, placed.order().id().toString(),
                new Events.OrderReserved(placed.order().id(), 5L, new BigDecimal("370.35"), 0))));

        var detail = orderService.get(user, placed.order().id());
        assertThat(detail.order().status()).isEqualTo(OrderStatus.FILLED);
        assertThat(detail.trades()).singleElement()
                .satisfies(t -> assertThat(t.grossAmount()).isEqualByComparingTo("370.35"));
        assertThat(orderService.list(user, OrderStatus.FILLED, "tcs", null, null, null, 0, 10).getTotalElements())
                .isEqualTo(1);
    }
}
