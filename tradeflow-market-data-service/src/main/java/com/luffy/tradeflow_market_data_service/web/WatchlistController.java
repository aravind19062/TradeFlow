package com.luffy.tradeflow_market_data_service.web;

import com.luffy.tradeflow.common.security.AuthenticatedUser;
import com.luffy.tradeflow_market_data_service.service.WatchlistService;
import com.luffy.tradeflow_market_data_service.web.MarketDtos.QuoteResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/watchlist")
@Tag(name = "Watchlist")
public class WatchlistController {

    private final WatchlistService watchlistService;

    public WatchlistController(WatchlistService watchlistService) {
        this.watchlistService = watchlistService;
    }

    @GetMapping
    @Operation(summary = "My watchlist with latest simulated quotes")
    public List<QuoteResponse> watchlist(AuthenticatedUser user) {
        return watchlistService.quotes(user.id());
    }

    @PutMapping("/{symbol}")
    @Operation(summary = "Add a symbol (idempotent)")
    public List<QuoteResponse> add(AuthenticatedUser user, @PathVariable String symbol) {
        watchlistService.add(user.id(), symbol);
        return watchlistService.quotes(user.id());
    }

    @DeleteMapping("/{symbol}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remove a symbol (idempotent)")
    public void remove(AuthenticatedUser user, @PathVariable String symbol) {
        watchlistService.remove(user.id(), symbol);
    }
}
