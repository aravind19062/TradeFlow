package com.luffy.tradeflow_live_trading_service;

import com.luffy.tradeflow.common.testsupport.TestJwts;
import com.luffy.tradeflow_live_trading_service.broker.BrokerAdapter;
import com.luffy.tradeflow_live_trading_service.broker.DisabledBrokerAdapter;
import com.luffy.tradeflow_live_trading_service.config.LiveConfig;
import com.luffy.tradeflow_live_trading_service.config.LiveProperties;
import com.luffy.tradeflow_live_trading_service.platform.PlatformChecks;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The production configuration: no broker is approved, so nothing can reach a broker. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NoBrokerConfiguredTest {

    @Autowired MockMvc mvc;
    @Autowired BrokerAdapter broker;
    @MockitoBean PlatformChecks platform;

    @Test
    void onlyTheDisabledAdapterExistsAndEveryBrokerActionIsRefused() throws Exception {
        assertThat(broker).isInstanceOf(DisabledBrokerAdapter.class);
        when(platform.eligibility(anyLong())).thenAnswer(inv -> new PlatformChecks.Eligibility(inv.getArgument(0),
                "QUALIFIED", true, List.of(), 1, Instant.now(), null, null, Instant.now()));
        when(platform.session(anyLong())).thenAnswer(inv -> new PlatformChecks.SessionState(inv.getArgument(0), true, 0, true));
        String stepUp = TestJwts.bearer(TestJwts.withAudience(5, "tradeflow-live",
                Map.of("amr", List.of("pwd", "otp"), "auth_time", Instant.now().getEpochSecond(), "tv", 0)));

        mvc.perform(get("/api/live/status").header("Authorization", stepUp)).andExpect(status().isOk())
                .andExpect(jsonPath("$.brokerConfigured").value(false))
                .andExpect(jsonPath("$.broker").value("none"));
        mvc.perform(post("/api/live/broker/connect").header("Authorization", stepUp))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("BROKER_NOT_CONFIGURED"));
        mvc.perform(get("/api/live/broker/orders").header("Authorization", stepUp))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("BROKER_NOT_CONFIGURED"));
        mvc.perform(post("/api/live/orders/preview").header("Authorization", stepUp).contentType("application/json")
                        .content("{\"exchange\":\"NSE\",\"symbol\":\"TCS\",\"side\":\"BUY\",\"type\":\"LIMIT\",\"quantity\":1,\"limitPrice\":10}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NO_BROKER_CONNECTION"));
    }

    @Test
    void refusesToStartWithAnUnapprovedBroker() {
        LiveProperties props = new LiveProperties("somebroker", null, null, null, null, null, null, null);
        var fyers = new com.luffy.tradeflow_live_trading_service.broker.fyers.FyersProperties(null, null, null, null, null, null, null);
        var json = tools.jackson.databind.json.JsonMapper.builder().build();
        assertThatThrownBy(() -> new LiveConfig().brokerAdapter(props, fyers, json, java.time.Clock.systemUTC()))
                .hasMessageContaining("not integrated");
        // Fyers is approved, but never starts without its app credentials.
        LiveProperties fyersSelected = new LiveProperties("fyers", null, null, null, null, null, null, null);
        assertThatThrownBy(() -> new LiveConfig().brokerAdapter(fyersSelected, fyers, json, java.time.Clock.systemUTC()))
                .hasMessageContaining("TRADEFLOW_FYERS_APP_ID");
        assertThatThrownBy(() -> new LiveProperties(null, null, null, null, null, java.time.Duration.ofMinutes(30), null, null))
                .hasMessageContaining("at most 15 minutes");
    }
}
