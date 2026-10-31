package com.luffy.tradeflow_order_service;

import com.luffy.tradeflow.common.events.EventCodec;
import com.luffy.tradeflow.common.events.Events;
import com.luffy.tradeflow.common.events.Topics;
import com.luffy.tradeflow.common.testsupport.TestJwts;
import com.luffy.tradeflow_order_service.client.MarketDataClient;
import com.luffy.tradeflow_order_service.domain.Order;
import com.luffy.tradeflow_order_service.domain.OrderStatus;
import com.luffy.tradeflow_order_service.messaging.OrderEventListener;
import com.luffy.tradeflow_order_service.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * D2: the demo engine trades at the simulated touch (buy at the ask, sell at the bid) and supports
 * SELL stop / stop-limit orders that trigger on the bid. Quotes here: mid 250, bid 249.75, ask 250.25.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SpreadAndStopOrderTest {

    private static final long USER = 77;
    private static final AtomicLong TICK = new AtomicLong(10_000);

    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired EventCodec codec;
    @Autowired OrderEventListener listener;
    @Autowired OrderRepository orders;
    @MockitoBean MarketDataClient marketData;

    @BeforeEach
    void quotes() {
        when(marketData.quote(anyString())).thenAnswer(inv -> new MarketDataClient.Quote(inv.getArgument(0),
                new BigDecimal("250.00"), 10, Instant.now(), "SIMULATED", new BigDecimal("249.75"),
                new BigDecimal("250.25")));
    }

    private ResultActions submit(String body) throws Exception {
        return mvc.perform(post("/api/orders").header("Authorization", TestJwts.bearer(TestJwts.user(USER)))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private UUID place(String body) throws Exception {
        String out = submit(body).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(out).get("id").asString());
    }

    private void reserve(UUID id, String cash, long qty) {
        listener.onReserved(codec.encode(codec.wrap(Topics.ORDERS_RESERVED, id.toString(),
                new Events.OrderReserved(id, USER, new BigDecimal(cash), qty))));
    }

    private void tick(String symbol, String mid, String bid, String ask) {
        listener.onPriceUpdated(codec.encode(codec.wrap(Topics.PRICES_UPDATED, symbol, new Events.PriceUpdated(symbol,
                new BigDecimal(mid), TICK.incrementAndGet(), Instant.now(), "SIMULATED", new BigDecimal(bid),
                new BigDecimal(ask)))));
    }

    private Order order(UUID id) {
        return orders.findById(id).orElseThrow();
    }

    @Test
    void marketBuyPaysTheAskAndMarketSellReceivesTheBid() throws Exception {
        UUID buy = place("{\"symbol\":\"SPRD\",\"side\":\"BUY\",\"type\":\"MARKET\",\"quantity\":10}");
        assertThat(order(buy).getReservationPrice()).isEqualByComparingTo("250.25");
        reserve(buy, "2502.50", 0);
        assertThat(order(buy).getAverageFillPrice()).isEqualByComparingTo("250.25");

        UUID sell = place("{\"symbol\":\"SPRD\",\"side\":\"SELL\",\"type\":\"MARKET\",\"quantity\":10}");
        reserve(sell, "0", 10);
        assertThat(order(sell).getAverageFillPrice()).isEqualByComparingTo("249.75");
    }

    @Test
    void limitOrdersCompareTheirLimitWithTheOppositeSideOfTheQuote() throws Exception {
        tick("LIMS", "100.00", "99.90", "100.10");
        UUID buy = place("{\"symbol\":\"LIMS\",\"side\":\"BUY\",\"type\":\"LIMIT\",\"quantity\":1,\"limitPrice\":100.00}");
        reserve(buy, "100.00", 0);
        // The mid is at the limit, but the ask (100.10) is not: a buyer cannot trade at the mid.
        assertThat(order(buy).getStatus()).isEqualTo(OrderStatus.OPEN);
        tick("LIMS", "99.90", "99.80", "100.00");
        assertThat(order(buy).getStatus()).isEqualTo(OrderStatus.FILLED);
        assertThat(order(buy).getAverageFillPrice()).isEqualByComparingTo("100.00");
    }

    @Test
    void sellStopRestsUntilTheBidFallsToTheTriggerThenSellsAtTheBid() throws Exception {
        tick("STP", "500.00", "499.75", "500.25");
        UUID id = place("{\"symbol\":\"STP\",\"side\":\"SELL\",\"type\":\"STOP\",\"quantity\":3,\"triggerPrice\":240.00}");
        assertThat(order(id).getTriggerPrice()).isEqualByComparingTo("240.00");
        reserve(id, "0", 3);
        assertThat(order(id).getStatus()).isEqualTo(OrderStatus.OPEN);

        tick("STP", "245.00", "244.80", "245.20");
        assertThat(order(id).getStatus()).isEqualTo(OrderStatus.OPEN);
        assertThat(order(id).getTriggeredAt()).isNull();

        // A gap through the stop: the order triggers and fills at the (lower) bid, not at the trigger.
        tick("STP", "231.00", "230.80", "231.20");
        Order filled = order(id);
        assertThat(filled.getStatus()).isEqualTo(OrderStatus.FILLED);
        assertThat(filled.getTriggeredAt()).isNotNull();
        assertThat(filled.getAverageFillPrice()).isEqualByComparingTo("230.80");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/orders/" + id)
                        .header("Authorization", TestJwts.bearer(TestJwts.user(USER))))
                .andExpect(jsonPath("$.order.triggerPrice").value(240.0))
                .andExpect(jsonPath("$.order.triggeredAt").exists())
                .andExpect(jsonPath("$.history[?(@.reason =~ /Triggered: bid 230.80.*/)]").exists());
    }

    @Test
    void sellStopLimitTriggersThenOnlySellsAtOrAboveTheLimit() throws Exception {
        tick("STL", "250.00", "249.75", "250.25");
        UUID id = place("{\"symbol\":\"STL\",\"side\":\"SELL\",\"type\":\"STOP_LIMIT\",\"quantity\":2,"
                + "\"triggerPrice\":240.00,\"limitPrice\":238.00}");
        reserve(id, "0", 2);

        // Gaps below the limit: triggered, but no fill (that is the risk of a stop-limit).
        tick("STL", "230.00", "229.80", "230.20");
        Order resting = order(id);
        assertThat(resting.getStatus()).isEqualTo(OrderStatus.OPEN);
        assertThat(resting.getTriggeredAt()).isNotNull();

        // Recovers to the limit: fills at the bid, which respects the limit.
        tick("STL", "238.30", "238.10", "238.50");
        assertThat(order(id).getStatus()).isEqualTo(OrderStatus.FILLED);
        assertThat(order(id).getAverageFillPrice()).isEqualByComparingTo("238.10");
    }

    @Test
    void openStopOrdersCanBeCancelled() throws Exception {
        UUID id = place("{\"symbol\":\"CXL\",\"side\":\"SELL\",\"type\":\"STOP\",\"quantity\":1,\"triggerPrice\":200.00}");
        reserve(id, "0", 1);
        mvc.perform(post("/api/orders/" + id + "/cancel").header("Authorization", TestJwts.bearer(TestJwts.user(USER))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void stopOrdersAreValidated() throws Exception {
        submit("{\"symbol\":\"V\",\"side\":\"BUY\",\"type\":\"STOP\",\"quantity\":1,\"triggerPrice\":300.00}")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("STOP_BUY_NOT_SUPPORTED"));
        submit("{\"symbol\":\"V\",\"side\":\"SELL\",\"type\":\"STOP\",\"quantity\":1}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("TRIGGER_PRICE_REQUIRED"));
        submit("{\"symbol\":\"V\",\"side\":\"SELL\",\"type\":\"STOP\",\"quantity\":1,\"triggerPrice\":200,\"limitPrice\":199}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("LIMIT_PRICE_NOT_ALLOWED"));
        submit("{\"symbol\":\"V\",\"side\":\"SELL\",\"type\":\"STOP_LIMIT\",\"quantity\":1,\"triggerPrice\":200,\"limitPrice\":201}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("LIMIT_ABOVE_TRIGGER"));
        submit("{\"symbol\":\"V\",\"side\":\"SELL\",\"type\":\"LIMIT\",\"quantity\":1,\"limitPrice\":260,\"triggerPrice\":200}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("TRIGGER_PRICE_NOT_ALLOWED"));
        // At or above the current bid (249.75) it would trigger immediately: refused.
        submit("{\"symbol\":\"V\",\"side\":\"SELL\",\"type\":\"STOP\",\"quantity\":1,\"triggerPrice\":249.75}")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("STOP_ALREADY_TRIGGERED"));
    }
}
