package com.luffy.tradeflow.common.config;

import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.http.client.HttpComponentsClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.autoconfigure.ClientHttpRequestFactoryBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Outbound HTTP clients never keep a cookie jar: a cookie received while serving one user must
 * never be sent on behalf of another. (Apache HttpClient, present via the Eureka client, manages
 * cookies by default.)
 */
@Configuration
@ConditionalOnClass(name = "org.apache.hc.client5.http.impl.classic.HttpClientBuilder")
public class StatelessHttpClientConfig {

    @Bean
    public ClientHttpRequestFactoryBuilderCustomizer<HttpComponentsClientHttpRequestFactoryBuilder> noOutboundCookieStore() {
        return builder -> builder.withHttpClientCustomizer(HttpClientBuilder::disableCookieManagement);
    }
}
