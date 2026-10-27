package com.luffy.tradeflow.common.config;

import com.luffy.tradeflow.common.security.BearerTokenRelayInterceptor;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;
import java.time.Duration;

@Configuration
@EnableScheduling
public class CommonConfig {

    /** Injected everywhere instead of calling Instant.now(), so tests can control time. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /** Every RestClient relays the caller's JWT and fails fast instead of hanging. */
    @Bean
    public RestClientCustomizer tradeflowRestClientCustomizer() {
        return builder -> {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(Duration.ofSeconds(2));
            factory.setReadTimeout(Duration.ofSeconds(5));
            builder.requestFactory(factory).requestInterceptor(new BearerTokenRelayInterceptor());
        };
    }
}
