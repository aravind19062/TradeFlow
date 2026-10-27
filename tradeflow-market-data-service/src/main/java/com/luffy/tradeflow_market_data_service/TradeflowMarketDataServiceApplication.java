package com.luffy.tradeflow_market_data_service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {"com.luffy.tradeflow_market_data_service", "com.luffy.tradeflow.common"})
public class TradeflowMarketDataServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(TradeflowMarketDataServiceApplication.class, args);
	}

}
