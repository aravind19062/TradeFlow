package com.luffy.tradeflow_user_service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@SpringBootApplication(scanBasePackages = {"com.luffy.tradeflow_user_service", "com.luffy.tradeflow.common"})
public class TradeflowUserServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(TradeflowUserServiceApplication.class, args);
	}
	@Bean
	public BCryptPasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}
}
