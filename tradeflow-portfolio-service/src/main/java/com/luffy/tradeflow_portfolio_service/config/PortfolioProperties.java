package com.luffy.tradeflow_portfolio_service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

/**
 * @param initialCash   DEMO: simulated cash credited when a user registers
 * @param currency      reporting currency of the simulated market
 * @param marketDataUrl base URL of tradeflow-market-data-service
 * @param orderServiceUrl base URL of tradeflow-order-service (used by the assistant)
 */
@ConfigurationProperties("tradeflow.portfolio")
public record PortfolioProperties(BigDecimal initialCash, String currency, String marketDataUrl,
                                  String orderServiceUrl) {

    public PortfolioProperties {
        initialCash = initialCash == null ? new BigDecimal("1000000.00") : initialCash;
        currency = currency == null ? "INR" : currency;
        marketDataUrl = marketDataUrl == null ? "http://localhost:8083" : marketDataUrl;
        orderServiceUrl = orderServiceUrl == null ? "http://localhost:8080" : orderServiceUrl;
    }
}
