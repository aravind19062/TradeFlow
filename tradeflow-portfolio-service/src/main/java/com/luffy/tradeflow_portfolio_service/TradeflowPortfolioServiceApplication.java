package com.luffy.tradeflow_portfolio_service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@org.springframework.cloud.openfeign.EnableFeignClients(basePackages = "com.luffy.tradeflow_portfolio_service.client")
@SpringBootApplication(scanBasePackages = {"com.luffy.tradeflow_portfolio_service", "com.luffy.tradeflow.common"})
public class TradeflowPortfolioServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(TradeflowPortfolioServiceApplication.class, args);
	}

}
