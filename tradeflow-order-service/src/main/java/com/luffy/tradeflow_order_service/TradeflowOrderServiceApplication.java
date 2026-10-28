package com.luffy.tradeflow_order_service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@org.springframework.cloud.openfeign.EnableFeignClients(basePackages = "com.luffy.tradeflow_order_service.client")
@SpringBootApplication(scanBasePackages = {"com.luffy.tradeflow_order_service", "com.luffy.tradeflow.common"})
public class TradeflowOrderServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(TradeflowOrderServiceApplication.class, args);
	}

}
