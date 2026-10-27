package com.luffy.tradeflow_market_data_service.config;

import com.luffy.tradeflow_market_data_service.service.MarketSimulationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Seeds price history on startup and, when enabled, ticks the simulated market on a timer. */
@Slf4j
@Component
public class SimulationScheduler {

    private final MarketSimulationService simulationService;

    public SimulationScheduler(MarketSimulationService simulationService) {
        this.simulationService = simulationService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seedOnStartup() {
        simulationService.seedHistoryIfNeeded();
    }

    @Component
    @ConditionalOnProperty(name = "tradeflow.market.simulation.enabled", havingValue = "true")
    static class Ticker {

        private final MarketSimulationService simulationService;

        Ticker(MarketSimulationService simulationService) {
            this.simulationService = simulationService;
        }

        @Scheduled(fixedDelayString = "${tradeflow.market.simulation.tick-interval-ms:30000}",
                initialDelayString = "${tradeflow.market.simulation.tick-interval-ms:30000}")
        void tick() {
            try {
                simulationService.advance(1);
            } catch (RuntimeException e) {
                log.warn("Simulated tick failed: {}", e.getMessage());
            }
        }
    }
}
