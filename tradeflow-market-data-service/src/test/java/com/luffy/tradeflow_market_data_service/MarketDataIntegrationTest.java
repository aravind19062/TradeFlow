package com.luffy.tradeflow_market_data_service;

import com.luffy.tradeflow.common.outbox.OutboxEvent;
import com.luffy.tradeflow.common.outbox.OutboxEventRepository;
import com.luffy.tradeflow.common.testsupport.TestJwts;
import com.luffy.tradeflow_market_data_service.service.MarketSimulationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MarketDataIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired MarketSimulationService simulation;
    @Autowired OutboxEventRepository outbox;

    private JsonNode getJson(String url) throws Exception {
        return json.readTree(mvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString());
    }

    @Test
    void seededHistoryAndPublicQuotes() throws Exception {
        mvc.perform(get("/api/market/instruments")).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(14)));
        mvc.perform(get("/api/market/quotes")).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(14)))
                .andExpect(jsonPath("$[0].source").value("SIMULATED"));
        mvc.perform(get("/api/market/quotes?symbols=tcs,INFY")).andExpect(jsonPath("$", hasSize(2)));
        mvc.perform(get("/api/market/quotes/NOPE")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("UNKNOWN_SYMBOL"));

        JsonNode history = getJson("/api/market/history/TCS?limit=1000");
        JsonNode points = history.get("points");
        assertThat(points.size()).isGreaterThanOrEqualTo(90);
        assertThat(points.get(0).get("tick").asLong()).isZero();
        assertThat(points.get(0).get("price").decimalValue()).isEqualByComparingTo("3900.00"); // base price
        for (int i = 1; i < points.size(); i++) {
            assertThat(points.get(i).get("tick").asLong()).isGreaterThan(points.get(i - 1).get("tick").asLong());
        }
    }

    @Test
    void onlyAdminsCanAdvanceAndEachTickPublishesPrices() throws Exception {
        mvc.perform(post("/api/market/simulation/advance")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/market/simulation/advance").header("Authorization", TestJwts.bearer(TestJwts.user(1))))
                .andExpect(status().isForbidden());

        long before = getJson("/api/market/simulation").get("currentTick").asLong();
        long published = outbox.findAll().stream().map(OutboxEvent::getTopic).filter("prices.updated"::equals).count();
        mvc.perform(post("/api/market/simulation/advance?ticks=2")
                        .header("Authorization", TestJwts.bearer(TestJwts.admin(1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentTick").value(before + 2));
        long after = outbox.findAll().stream().map(OutboxEvent::getTopic).filter("prices.updated"::equals).count();
        assertThat(after - published).isEqualTo(14); // one event per symbol for the final tick
        assertThat(getJson("/api/market/quotes/TCS").get("tick").asLong()).isEqualTo(before + 2);
    }

    @Test
    void seedingIsIdempotent() throws Exception {
        long tick = getJson("/api/market/simulation").get("currentTick").asLong();
        simulation.seedHistoryIfNeeded();
        assertThat(getJson("/api/market/simulation").get("currentTick").asLong()).isEqualTo(tick);
    }

    @Test
    void watchlistIsPerUserAndIdempotent() throws Exception {
        String alice = TestJwts.bearer(TestJwts.user(11));
        String bob = TestJwts.bearer(TestJwts.user(12));
        mvc.perform(get("/api/watchlist")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/watchlist/tcs").header("Authorization", alice)).andExpect(status().isOk());
        mvc.perform(put("/api/watchlist/TCS").header("Authorization", alice)).andExpect(status().isOk());
        mvc.perform(put("/api/watchlist/ITC").header("Authorization", alice))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].symbol").value("TCS"));
        mvc.perform(get("/api/watchlist").header("Authorization", bob)).andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(put("/api/watchlist/NOPE").header("Authorization", alice)).andExpect(status().isNotFound());
        mvc.perform(delete("/api/watchlist/ITC").header("Authorization", alice)).andExpect(status().isNoContent());
        mvc.perform(get("/api/watchlist").header("Authorization", alice)).andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void alertsTriggerOnTicksAndAreOwnerScoped() throws Exception {
        String alice = TestJwts.bearer(TestJwts.user(21));
        String bob = TestJwts.bearer(TestJwts.user(22));
        mvc.perform(post("/api/alerts").header("Authorization", alice).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"symbol\":\"INFY\",\"direction\":\"ABOVE\",\"threshold\":0}"))
                .andExpect(status().isBadRequest());
        String body = mvc.perform(post("/api/alerts").header("Authorization", alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"symbol\":\"infy\",\"direction\":\"ABOVE\",\"threshold\":1.00,\"note\":\"always\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn().getResponse().getContentAsString();
        String id = json.readTree(body).get("id").asString();
        mvc.perform(post("/api/alerts").header("Authorization", alice).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"symbol\":\"INFY\",\"direction\":\"BELOW\",\"threshold\":1.00}"))
                .andExpect(status().isCreated());

        mvc.perform(delete("/api/alerts/" + id).header("Authorization", bob)).andExpect(status().isNotFound());
        simulation.advance(1);

        mvc.perform(get("/api/alerts?status=TRIGGERED").header("Authorization", alice))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(id));
        mvc.perform(get("/api/alerts?status=ACTIVE").header("Authorization", alice))
                .andExpect(jsonPath("$", hasSize(1)));
        mvc.perform(delete("/api/alerts/" + id).header("Authorization", alice))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
    }
}
