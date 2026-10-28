package com.luffy.tradeflow_api_gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;

/**
 * A registered instance that no longer accepts connections (container just stopped, registry
 * not yet updated) must reach the explicit 503 fallback, not surface as a 500.
 */
@SpringBootTest(properties =
        "spring.cloud.discovery.client.simple.instances.tradeflow-user-service[0].uri=http://127.0.0.1:1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GatewayUnreachableInstanceTest {

    @Autowired MockMvc mvc;

    @Test
    void connectionRefusedByTheInstanceIsAnExplicit503() throws Exception {
        mvc.perform(post("/api/auth/login").contentType("application/json")
                        .content("{\"email\":\"a@b.c\",\"password\":\"p\"}"))
                .andExpect(forwardedUrl("/fallback/user-service"));
    }
}
