package com.luffy.tradeflow_market_data_service.service;

import com.luffy.tradeflow.common.events.Events;
import com.luffy.tradeflow.common.events.Topics;
import com.luffy.tradeflow.common.money.Money;
import com.luffy.tradeflow.common.outbox.OutboxWriter;
import com.luffy.tradeflow_market_data_service.config.MarketProperties;
import com.luffy.tradeflow_market_data_service.domain.Instrument;
import com.luffy.tradeflow_market_data_service.domain.PriceAlert;
import com.luffy.tradeflow_market_data_service.domain.PriceTick;
import com.luffy.tradeflow_market_data_service.domain.SimulationState;
import com.luffy.tradeflow_market_data_service.repository.InstrumentRepository;
import com.luffy.tradeflow_market_data_service.repository.PriceAlertRepository;
import com.luffy.tradeflow_market_data_service.repository.PriceTickRepository;
import com.luffy.tradeflow_market_data_service.repository.SimulationStateRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Owns the simulation clock. Each tick, in one transaction: new prices are stored,
 * price alerts are evaluated, and a prices.updated event per symbol is written to
 * the outbox (order-service uses them to fill resting limit orders).
 */
@Slf4j
@Service
public class MarketSimulationService {

    public static final String SOURCE = "SIMULATED";

    private final SimulationStateRepository stateRepository;
    private final InstrumentRepository instrumentRepository;
    private final PriceTickRepository tickRepository;
    private final PriceAlertRepository alertRepository;
    private final OutboxWriter outboxWriter;
    private final MarketProperties properties;
    private final Clock clock;

    public MarketSimulationService(SimulationStateRepository stateRepository, InstrumentRepository instrumentRepository,
                                   PriceTickRepository tickRepository, PriceAlertRepository alertRepository,
                                   OutboxWriter outboxWriter, MarketProperties properties, Clock clock) {
        this.stateRepository = stateRepository;
        this.instrumentRepository = instrumentRepository;
        this.tickRepository = tickRepository;
        this.alertRepository = alertRepository;
        this.outboxWriter = outboxWriter;
        this.properties = properties;
        this.clock = clock;
    }

    /** Generates the deterministic price history once; a no-op when already seeded. */
    @Transactional
    public void seedHistoryIfNeeded() {
        SimulationState state = lockState();
        if (state.isSeeded()) {
            return;
        }
        List<Instrument> instruments = instrumentRepository.findAll(Sort.by("symbol"));
        int ticks = properties.historyTicks();
        Instant now = clock.instant();
        Map<String, BigDecimal> last = new HashMap<>();
        List<PriceTick> rows = new ArrayList<>(ticks * instruments.size());
        for (long tick = 0; tick < ticks; tick++) {
            Instant asOf = now.minus(properties.historySpacing().multipliedBy(ticks - 1 - tick));
            for (Instrument instrument : instruments) {
                BigDecimal price = tick == 0
                        ? Money.price(instrument.getBasePrice())
                        : PriceSimulator.nextPrice(properties.seed(), instrument.getSymbol(), tick,
                        last.get(instrument.getSymbol()), instrument.getDrift(), instrument.getVolatility());
                last.put(instrument.getSymbol(), price);
                rows.add(new PriceTick(instrument.getSymbol(), tick, price, asOf));
            }
        }
        tickRepository.saveAll(rows);
        long lastTick = ticks - 1;
        state.advanceTo(lastTick, now);
        last.forEach((symbol, price) -> publish(symbol, price, lastTick, now));
        log.info("Seeded {} simulated history ticks for {} instruments (seed={})", ticks, instruments.size(),
                properties.seed());
    }

    /** Advances the simulation clock by {@code count} ticks and returns the new tick. */
    @Transactional
    public long advance(int count) {
        SimulationState state = lockState();
        if (!state.isSeeded()) {
            throw new IllegalStateException("Price history has not been seeded yet");
        }
        List<Instrument> instruments = instrumentRepository.findAll(Sort.by("symbol"));
        Map<String, BigDecimal> last = tickRepository.findByTickIn(List.of(state.getCurrentTick())).stream()
                .collect(Collectors.toMap(PriceTick::getSymbol, PriceTick::getPrice));
        Map<String, Instrument> bySymbol = instruments.stream()
                .collect(Collectors.toMap(Instrument::getSymbol, Function.identity()));
        Instant now = clock.instant();
        long tick = state.getCurrentTick();
        for (int i = 0; i < count; i++) {
            tick++;
            List<PriceTick> rows = new ArrayList<>(instruments.size());
            for (Instrument instrument : instruments) {
                BigDecimal previous = last.getOrDefault(instrument.getSymbol(), instrument.getBasePrice());
                BigDecimal price = PriceSimulator.nextPrice(properties.seed(), instrument.getSymbol(), tick, previous,
                        instrument.getDrift(), instrument.getVolatility());
                last.put(instrument.getSymbol(), price);
                rows.add(new PriceTick(instrument.getSymbol(), tick, price, now));
            }
            tickRepository.saveAll(rows);
            evaluateAlerts(last, now);
        }
        state.advanceTo(tick, now);
        long finalTick = tick;
        last.forEach((symbol, price) -> {
            if (bySymbol.containsKey(symbol)) {
                publish(symbol, price, finalTick, now);
            }
        });
        log.debug("Advanced simulation to tick {}", tick);
        return tick;
    }

    private void evaluateAlerts(Map<String, BigDecimal> prices, Instant now) {
        for (PriceAlert alert : alertRepository.findBySymbolInAndStatus(prices.keySet(), PriceAlert.Status.ACTIVE)) {
            BigDecimal price = prices.get(alert.getSymbol());
            if (alert.isMetBy(price)) {
                alert.trigger(price, now);
                log.info("Price alert {} triggered for user {}: {} {} {} (price {})", alert.getId(),
                        alert.getUserId(), alert.getSymbol(), alert.getDirection(), alert.getThreshold(), price);
            }
        }
    }

    private void publish(String symbol, BigDecimal price, long tick, Instant asOf) {
        outboxWriter.append(Topics.PRICES_UPDATED, symbol, new Events.PriceUpdated(symbol, price, tick, asOf, SOURCE));
    }

    private SimulationState lockState() {
        return stateRepository.lockState()
                .orElseThrow(() -> new IllegalStateException("simulation_state row is missing; check migrations"));
    }
}
