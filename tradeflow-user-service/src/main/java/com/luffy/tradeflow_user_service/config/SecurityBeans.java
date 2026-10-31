package com.luffy.tradeflow_user_service.config;

import com.luffy.tradeflow.common.crypto.DataEncryptor;
import com.luffy.tradeflow_user_service.security.AuthProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AuthProperties.class)
public class SecurityBeans {

    /** Encrypts TOTP seeds at rest. Keys come from tradeflow.crypto.keyring (never from Git). */
    @Bean
    public DataEncryptor dataEncryptor(@Value("${tradeflow.crypto.keyring:}") String keyring) {
        return DataEncryptor.fromKeyRing(keyring);
    }
}
