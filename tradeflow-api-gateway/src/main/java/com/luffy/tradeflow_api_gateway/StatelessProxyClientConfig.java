package com.luffy.tradeflow_api_gateway;

import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.springframework.boot.http.client.HttpComponentsClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.autoconfigure.ClientHttpRequestFactoryBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * SECURITY: the proxy's HTTP client must not keep cookies. Apache HttpClient (on the classpath via
 * the Eureka client) manages cookies by default, which made the gateway store each backend
 * {@code Set-Cookie} (e.g. the refresh-token cookie) and replay it on later requests from OTHER
 * users. Cookies must travel only from a browser's own request. Regression test:
 * GatewayCookieIsolationTest.
 */
@Configuration
public class StatelessProxyClientConfig {

    @Bean
    public ClientHttpRequestFactoryBuilderCustomizer<HttpComponentsClientHttpRequestFactoryBuilder> noProxyCookieStore() {
        return builder -> builder.withHttpClientCustomizer(HttpClientBuilder::disableCookieManagement);
    }
}
