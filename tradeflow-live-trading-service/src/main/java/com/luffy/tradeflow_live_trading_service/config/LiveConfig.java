package com.luffy.tradeflow_live_trading_service.config;

import com.luffy.tradeflow.common.crypto.DataEncryptor;
import com.luffy.tradeflow_live_trading_service.broker.BrokerAdapter;
import com.luffy.tradeflow_live_trading_service.broker.DisabledBrokerAdapter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@Configuration
@EnableConfigurationProperties(LiveProperties.class)
@EntityScan(basePackages = {"com.luffy.tradeflow_live_trading_service", "com.luffy.tradeflow.common"})
@EnableJpaRepositories(basePackages = {"com.luffy.tradeflow_live_trading_service", "com.luffy.tradeflow.common"})
public class LiveConfig {

    /**
     * The broker adapter. Only "none" exists: no broker has been approved, so no broker code is
     * present. Any other value refuses to start rather than silently trading through something.
     */
    @Bean
    public BrokerAdapter brokerAdapter(LiveProperties properties) {
        if (!"none".equals(properties.broker())) {
            throw new IllegalStateException("Refusing to start: broker '" + properties.broker()
                    + "' is not integrated. No broker has been approved yet.");
        }
        return new DisabledBrokerAdapter();
    }

    /** Encrypts broker credentials at rest (separate key ring from user-service). */
    @Bean
    public DataEncryptor dataEncryptor(@Value("${tradeflow.crypto.keyring:}") String keyring) {
        return DataEncryptor.fromKeyRing(keyring);
    }
}
