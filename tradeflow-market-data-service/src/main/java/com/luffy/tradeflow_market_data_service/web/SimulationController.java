package com.luffy.tradeflow_market_data_service.web;

import com.luffy.tradeflow_market_data_service.service.MarketSimulationService;
import com.luffy.tradeflow_market_data_service.service.QuoteService;
import com.luffy.tradeflow_market_data_service.web.MarketDtos.SimulationResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/market/simulation")
@Tag(name = "Market simulation")
public class SimulationController {

    private static final String NOTE = "Prices come from a seeded random walk; they are not live market data.";

    private final MarketSimulationService simulationService;
    private final QuoteService quoteService;

    public SimulationController(MarketSimulationService simulationService, QuoteService quoteService) {
        this.simulationService = simulationService;
        this.quoteService = quoteService;
    }

    @GetMapping
    @Operation(summary = "Current simulation tick")
    public SimulationResponse state() {
        return new SimulationResponse(quoteService.currentTick(), MarketSimulationService.SOURCE, NOTE);
    }

    @PostMapping("/advance")
    @Operation(summary = "ADMIN: advance the simulated market by N ticks (deterministic demos)")
    public SimulationResponse advance(@RequestParam(defaultValue = "1") @Min(1) @Max(100) int ticks) {
        return new SimulationResponse(simulationService.advance(ticks), MarketSimulationService.SOURCE, NOTE);
    }
}
