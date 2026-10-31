package com.luffy.tradeflow_live_trading_service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * LIVE trading: real broker accounts and real money, physically separate from the simulated engine
 * (own database, own keys, LIVE events only). Every state-changing action passes LiveAccessGuard.
 * No broker is integrated until one is approved: the only adapter refuses every call.
 */
@SpringBootApplication(scanBasePackages = {"com.luffy.tradeflow_live_trading_service", "com.luffy.tradeflow.common"})
public class TradeflowLiveTradingServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(TradeflowLiveTradingServiceApplication.class, args);
    }
}
