package com.luffy.tradeflow_learning_service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Trading Academy and trading readiness. Environment-neutral (PLATFORM): it holds no trading
 * data, reads DEMO events only as evidence for practical exercises, and is the sole authority
 * on whether a user is QUALIFIED for live trading.
 */
@SpringBootApplication(scanBasePackages = {"com.luffy.tradeflow_learning_service", "com.luffy.tradeflow.common"})
public class TradeflowLearningServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(TradeflowLearningServiceApplication.class, args);
    }
}
