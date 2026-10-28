package com.luffy.tradeflow_portfolio_service.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;

@FeignClient(name = "tradeflow-market-data-service", contextId = "portfolioMarketData")
public interface MarketDataFeignClient {

    @GetMapping("/api/market/quotes")
    List<MarketDataClient.Quote> quotes(@RequestParam("symbols") String symbols);

    @GetMapping("/api/market/history")
    Map<String, MarketDataClient.History> histories(@RequestParam("symbols") String symbols,
                                                    @RequestParam("since") String since,
                                                    @RequestParam("limit") int limit);

    @GetMapping("/api/watchlist")
    List<MarketDataClient.Quote> watchlist();

    @GetMapping("/api/alerts")
    List<MarketDataClient.Alert> alerts(@RequestParam(value = "status", required = false) String status);
}
