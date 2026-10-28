package com.luffy.tradeflow_portfolio_service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

/**
 * @param initialCash   DEMO: simulated cash credited when a user registers
 * @param currency      reporting currency of the simulated market
 */
@ConfigurationProperties("tradeflow.portfolio")
public record PortfolioProperties(BigDecimal initialCash, String currency) {

    public PortfolioProperties {
        initialCash = initialCash == null ? new BigDecimal("1000000.00") : initialCash;
        currency = currency == null ? "INR" : currency;
    }
}
