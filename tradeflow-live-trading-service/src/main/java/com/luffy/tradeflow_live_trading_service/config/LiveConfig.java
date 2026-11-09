package com.luffy.tradeflow_live_trading_service.config;

import com.luffy.tradeflow.common.crypto.DataEncryptor;
import com.luffy.tradeflow_live_trading_service.broker.BrokerAdapter;
import com.luffy.tradeflow_live_trading_service.broker.DisabledBrokerAdapter;
import com.luffy.tradeflow_live_trading_service.broker.angelone.AngelOneBrokerAdapter;
import com.luffy.tradeflow_live_trading_service.broker.angelone.AngelOneProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;

@Configuration
@EnableConfigurationProperties({LiveProperties.class, AngelOneProperties.class})
@EntityScan(basePackages = {"com.luffy.tradeflow_live_trading_service", "com.luffy.tradeflow.common"})
@EnableJpaRepositories(basePackages = {"com.luffy.tradeflow_live_trading_service", "com.luffy.tradeflow.common"})
public class LiveConfig {

    /**
     * The broker adapter: "none" (default; refuses everything) or "angelone" (Angel One SmartAPI,
     * replacing Fyers on 2026-11-08 at the owner's request; read-only in phase 6: orders are refused
     * by the adapter itself). Any other value, or "angelone" without its API key, refuses to start
     * rather than silently trading through something.
     */
    @Bean
    public BrokerAdapter brokerAdapter(LiveProperties properties, AngelOneProperties angelOne, JsonMapper json, Clock clock) {
        return switch (properties.broker()) {
            case "none" -> new DisabledBrokerAdapter();
            case "angelone" -> new AngelOneBrokerAdapter(angelOne, json, clock);
            default -> throw new IllegalStateException("Refusing to start: broker '" + properties.broker()
                    + "' is not integrated. Supported: none, angelone.");
        };
    }

    /** Encrypts broker credentials at rest (separate key ring from user-service). */
    @Bean
    public DataEncryptor dataEncryptor(@Value("${tradeflow.crypto.keyring:}") String keyring) {
        return DataEncryptor.fromKeyRing(keyring);
    }
}
