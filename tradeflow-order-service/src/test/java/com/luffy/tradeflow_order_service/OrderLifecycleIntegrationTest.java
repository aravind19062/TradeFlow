package com.luffy.tradeflow_order_service;

import com.luffy.tradeflow.common.events.EventCodec;
import com.luffy.tradeflow.common.events.Events;
import com.luffy.tradeflow.common.events.Topics;
import com.luffy.tradeflow.common.outbox.OutboxEvent;
import com.luffy.tradeflow.common.outbox.OutboxEventRepository;
import com.luffy.tradeflow.common.testsupport.TestJwts;
import com.luffy.tradeflow_order_service.client.MarketDataClient;
import com.luffy.tradeflow_order_service.domain.OrderStatus;
import com.luffy.tradeflow_order_service.messaging.OrderEventListener;
import com.luffy.tradeflow_order_service.repository.OrderRepository;
import com.luffy.tradeflow_order_service.repository.TradeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full order lifecycle against H2 with the real web, security, JPA and listener
 * code. Kafka delivery is simulated by calling the listener with encoded events,
 * exactly as the Kafka container would.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrderLifecycleIntegrationTest {

    private static final long USER = 41;
    private static final long OTHER_USER = 42;

    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired EventCodec codec;
    @Autowired OrderEventListener listener;
    @Autowired OrderRepository orderRepository;
    @Autowired TradeRepository tradeRepository;
    @Autowired OutboxEventRepository outboxRepository;
    @MockitoBean MarketDataClient marketDataClient;

    @BeforeEach
    void quotes() {
        when(marketDataClient.quote(anyString())).thenAnswer(inv -> new MarketDataClient.Quote(inv.getArgument(0),
                new BigDecimal("250.00"), 10, Instant.now(), "SIMULATED"));
    }

    private UUID place(long user, String body) throws Exception {
        MvcResult result = mvc.perform(post("/api/orders").header("Authorization", TestJwts.bearer(TestJwts.user(user)))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn();
        return UUID.fromString(json.readTree(result.getResponse().getContentAsString()).get("id").asString());
    }

    private String envelope(String topic, String key, Object payload) {
        return codec.encode(codec.wrap(topic, key, payload));
    }

    private JsonNode getOrder(long user, UUID id) throws Exception {
        String body = mvc.perform(get("/api/orders/" + id).header("Authorization", TestJwts.bearer(TestJwts.user(user))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private long outboxCount(String topic) {
        return outboxRepository.findAll().stream().map(OutboxEvent::getTopic).filter(topic::equals).count();
    }

    @Test
    void marketOrderIsFilledAtAcceptedQuoteOnceReserved() throws Exception {
        long placedBefore = outboxCount(Topics.ORDERS_PLACED);
        UUID id = place(USER, "{\"symbol\":\"TCS\",\"side\":\"BUY\",\"type\":\"MARKET\",\"quantity\":4}");
        assertThat(outboxCount(Topics.ORDERS_PLACED)).isEqualTo(placedBefore + 1);

        String reserved = envelope(Topics.ORDERS_RESERVED, id.toString(),
                new Events.OrderReserved(id, USER, new BigDecimal("1000.00"), 0));
        listener.onReserved(reserved);
        listener.onReserved(reserved); // duplicate delivery of the same event

        JsonNode detail = getOrder(USER, id);
        assertThat(detail.at("/order/status").asString()).isEqualTo("FILLED");
        assertThat(detail.at("/order/averageFillPrice").decimalValue()).isEqualByComparingTo("250.00");
        assertThat(detail.at("/trades").size()).isEqualTo(1);
        assertThat(detail.at("/trades/0/grossAmount").decimalValue()).isEqualByComparingTo("1000.00");
        assertThat(tradeRepository.findByOrderIdOrderByExecutedAtAsc(id)).hasSize(1);
    }

    @Test
    void limitOrderRestsThenFillsOnMarketablePriceTick() throws Exception {
        UUID id = place(USER, "{\"symbol\":\"INFY\",\"side\":\"BUY\",\"type\":\"LIMIT\",\"quantity\":2,"
                + "\"limitPrice\":200.00}");
        listener.onReserved(envelope(Topics.ORDERS_RESERVED, id.toString(),
                new Events.OrderReserved(id, USER, new BigDecimal("400.00"), 0)));
        assertThat(orderRepository.findById(id).orElseThrow().getStatus()).isEqualTo(OrderStatus.OPEN);

        listener.onPriceUpdated(envelope(Topics.PRICES_UPDATED, "INFY",
                new Events.PriceUpdated("INFY", new BigDecimal("205.00"), 100, Instant.now(), "SIMULATED")));
        assertThat(orderRepository.findById(id).orElseThrow().getStatus()).isEqualTo(OrderStatus.OPEN);

        listener.onPriceUpdated(envelope(Topics.PRICES_UPDATED, "INFY",
                new Events.PriceUpdated("INFY", new BigDecimal("199.50"), 101, Instant.now(), "SIMULATED")));
        var order = orderRepository.findById(id).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.FILLED);
        assertThat(order.getAverageFillPrice()).isEqualByComparingTo("199.50");
    }

    @Test
    void staleOutOfOrderTickDoesNotFillOrders() throws Exception {
        listener.onPriceUpdated(envelope(Topics.PRICES_UPDATED, "LT",
                new Events.PriceUpdated("LT", new BigDecimal("500.00"), 200, Instant.now(), "SIMULATED")));
        UUID id = place(USER, "{\"symbol\":\"LT\",\"side\":\"BUY\",\"type\":\"LIMIT\",\"quantity\":1,"
                + "\"limitPrice\":400.00}");
        listener.onReserved(envelope(Topics.ORDERS_RESERVED, id.toString(),
                new Events.OrderReserved(id, USER, new BigDecimal("400.00"), 0)));
        listener.onPriceUpdated(envelope(Topics.PRICES_UPDATED, "LT",
                new Events.PriceUpdated("LT", new BigDecimal("300.00"), 150, Instant.now(), "SIMULATED")));
        assertThat(orderRepository.findById(id).orElseThrow().getStatus()).isEqualTo(OrderStatus.OPEN);
    }

    @Test
    void rejectionAndCancellationRules() throws Exception {
        UUID rejected = place(USER, "{\"symbol\":\"SBIN\",\"side\":\"BUY\",\"type\":\"MARKET\",\"quantity\":1}");
        // Cancelling while the reservation is in flight is refused.
        mvc.perform(post("/api/orders/" + rejected + "/cancel")
                        .header("Authorization", TestJwts.bearer(TestJwts.user(USER))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_CANCELLABLE_YET"));
        listener.onRejected(envelope(Topics.ORDERS_REJECTED, rejected.toString(),
                new Events.OrderRejected(rejected, USER, "INSUFFICIENT_FUNDS", "needs more cash")));
        JsonNode detail = getOrder(USER, rejected);
        assertThat(detail.at("/order/status").asString()).isEqualTo("REJECTED");
        assertThat(detail.at("/order/rejectCode").asString()).isEqualTo("INSUFFICIENT_FUNDS");

        UUID open = place(USER, "{\"symbol\":\"ITC\",\"side\":\"BUY\",\"type\":\"LIMIT\",\"quantity\":1,"
                + "\"limitPrice\":1.00}");
        listener.onReserved(envelope(Topics.ORDERS_RESERVED, open.toString(),
                new Events.OrderReserved(open, USER, new BigDecimal("1.00"), 0)));
        long cancelledBefore = outboxCount(Topics.ORDERS_CANCELLED);
        mvc.perform(post("/api/orders/" + open + "/cancel").header("Authorization", TestJwts.bearer(TestJwts.user(USER))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(outboxCount(Topics.ORDERS_CANCELLED)).isEqualTo(cancelledBefore + 1);
        mvc.perform(post("/api/orders/" + open + "/cancel").header("Authorization", TestJwts.bearer(TestJwts.user(USER))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
    }

    @Test
    void usersCannotSeeOrCancelEachOthersOrders() throws Exception {
        UUID id = place(USER, "{\"symbol\":\"TCS\",\"side\":\"BUY\",\"type\":\"MARKET\",\"quantity\":1}");
        mvc.perform(get("/api/orders/" + id).header("Authorization", TestJwts.bearer(TestJwts.user(OTHER_USER))))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/orders/" + id + "/cancel")
                        .header("Authorization", TestJwts.bearer(TestJwts.user(OTHER_USER))))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/orders").header("Authorization", TestJwts.bearer(TestJwts.user(OTHER_USER))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void validationAuthenticationAndPagination() throws Exception {
        mvc.perform(get("/api/orders")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(post("/api/orders").header("Authorization", TestJwts.bearer(TestJwts.user(USER)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"symbol\":\"TCS\",\"side\":\"BUY\",\"type\":\"MARKET\",\"quantity\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("quantity"));
        mvc.perform(post("/api/orders").header("Authorization", TestJwts.bearer(TestJwts.user(USER)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"symbol\":\"TCS\",\"side\":\"HOLD\",\"type\":\"MARKET\",\"quantity\":1}"))
                .andExpect(status().isBadRequest());

        long user = 77;
        for (int i = 0; i < 3; i++) {
            place(user, "{\"symbol\":\"HDFCBANK\",\"side\":\"BUY\",\"type\":\"MARKET\",\"quantity\":1}");
        }
        mvc.perform(get("/api/orders?size=2&page=0&symbol=hdfcbank&status=PENDING")
                        .header("Authorization", TestJwts.bearer(TestJwts.user(user))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.content.length()").value(2));
        mvc.perform(get("/api/orders?size=500").header("Authorization", TestJwts.bearer(TestJwts.user(user))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PAGE"));
    }

    @Test
    void clientOrderIdMakesPlacementIdempotent() throws Exception {
        String body = "{\"symbol\":\"TCS\",\"side\":\"BUY\",\"type\":\"MARKET\",\"quantity\":3,\"clientOrderId\":\"idem-1\"}";
        UUID first = place(USER, body);
        mvc.perform(post("/api/orders").header("Authorization", TestJwts.bearer(TestJwts.user(USER)))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(first.toString()));
    }
}
