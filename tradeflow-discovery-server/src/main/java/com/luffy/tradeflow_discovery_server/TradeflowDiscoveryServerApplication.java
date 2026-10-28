package com.luffy.tradeflow_discovery_server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.netflix.eureka.server.EnableEurekaServer;

/** Eureka registry: services register on startup and resolve each other by name. */
@SpringBootApplication
@EnableEurekaServer
public class TradeflowDiscoveryServerApplication {

	public static void main(String[] args) {
		SpringApplication.run(TradeflowDiscoveryServerApplication.class, args);
	}
}
