package com.luffy.tradeflow_order_service.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/** Declarative HTTP client; the name is resolved through Eureka and load-balanced. */
@FeignClient(name = "tradeflow-market-data-service", contextId = "orderMarketData")
public interface MarketDataFeignClient {

    @GetMapping("/api/market/quotes/{symbol}")
    MarketDataClient.Quote quote(@PathVariable("symbol") String symbol);
}
