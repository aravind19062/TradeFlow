package com.luffy.tradeflow_market_data_service.service;

import com.luffy.tradeflow.common.money.Money;
import com.luffy.tradeflow.common.web.ApiException;
import com.luffy.tradeflow_market_data_service.domain.Instrument;
import com.luffy.tradeflow_market_data_service.domain.PriceTick;
import com.luffy.tradeflow_market_data_service.domain.SimulationState;
import com.luffy.tradeflow_market_data_service.repository.InstrumentRepository;
import com.luffy.tradeflow_market_data_service.repository.PriceTickRepository;
import com.luffy.tradeflow_market_data_service.repository.SimulationStateRepository;
import com.luffy.tradeflow_market_data_service.web.MarketDtos.PricePoint;
import com.luffy.tradeflow_market_data_service.web.MarketDtos.QuoteResponse;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class QuoteService {

    public static final int MAX_HISTORY_POINTS = 1000;

    private final InstrumentRepository instrumentRepository;
    private final PriceTickRepository tickRepository;
    private final SimulationStateRepository stateRepository;

    public QuoteService(InstrumentRepository instrumentRepository, PriceTickRepository tickRepository,
                        SimulationStateRepository stateRepository) {
        this.instrumentRepository = instrumentRepository;
        this.tickRepository = tickRepository;
        this.stateRepository = stateRepository;
    }

    public List<Instrument> instruments() {
        return instrumentRepository.findAll(Sort.by("symbol"));
    }

    public Instrument instrument(String symbol) {
        return instrumentRepository.findById(normalize(symbol))
                .orElseThrow(() -> new ApiException(org.springframework.http.HttpStatus.NOT_FOUND, "UNKNOWN_SYMBOL",
                        "Unknown symbol " + symbol));
    }

    /** Latest quotes; all instruments when {@code symbols} is null or empty. */
    public List<QuoteResponse> quotes(Collection<String> symbols) {
        List<Instrument> instruments = symbols == null || symbols.isEmpty()
                ? instruments()
                : resolve(symbols);
        long tick = currentTick();
        if (tick < 0 || instruments.isEmpty()) {
            return List.of();
        }
        Set<String> wanted = instruments.stream().map(Instrument::getSymbol).collect(Collectors.toSet());
        Map<String, Map<Long, PriceTick>> ticks = new HashMap<>();
        for (PriceTick t : tickRepository.findBySymbolInAndTickIn(wanted, List.of(tick, tick - 1))) {
            ticks.computeIfAbsent(t.getSymbol(), s -> new HashMap<>()).put(t.getTick(), t);
        }
        List<QuoteResponse> quotes = new ArrayList<>();
        for (Instrument instrument : instruments) {
            Map<Long, PriceTick> byTick = ticks.getOrDefault(instrument.getSymbol(), Map.of());
            PriceTick current = byTick.get(tick);
            if (current == null) {
                continue;
            }
            PriceTick previous = byTick.get(tick - 1);
            BigDecimal prev = previous == null ? current.getPrice() : previous.getPrice();
            BigDecimal change = Money.price(current.getPrice().subtract(prev));
            quotes.add(new QuoteResponse(instrument.getSymbol(), instrument.getName(), instrument.getSector(),
                    instrument.getAssetClass(), instrument.getCurrency(), Money.price(current.getPrice()),
                    Money.price(prev), change, Money.ratio(change, prev), current.getTick(), current.getAsOf(),
                    MarketSimulationService.SOURCE));
        }
        return quotes;
    }

    public QuoteResponse quote(String symbol) {
        Instrument instrument = instrument(symbol);
        return quotes(List.of(instrument.getSymbol())).stream().findFirst()
                .orElseThrow(() -> ApiException.dependencyUnavailable("Price simulation",
                        "no prices have been generated yet"));
    }

    /** Ticks in ascending order; the most recent {@code limit} ticks when {@code since} is null. */
    public List<PricePoint> history(String symbol, Instant since, int limit) {
        Instrument instrument = instrument(symbol);
        int capped = Math.max(1, Math.min(limit, MAX_HISTORY_POINTS));
        List<PriceTick> ticks;
        if (since == null) {
            ticks = new ArrayList<>(tickRepository.findBySymbolOrderByTickDesc(instrument.getSymbol(),
                    Limit.of(capped)));
            ticks.sort(Comparator.comparingLong(PriceTick::getTick));
        } else {
            ticks = tickRepository.findBySymbolAndAsOfGreaterThanEqualOrderByTickAsc(instrument.getSymbol(), since,
                    Limit.of(capped));
        }
        return ticks.stream().map(t -> new PricePoint(t.getTick(), Money.price(t.getPrice()), t.getAsOf())).toList();
    }

    public long currentTick() {
        return stateRepository.readState().map(SimulationState::getCurrentTick).orElse(-1L);
    }

    private List<Instrument> resolve(Collection<String> symbols) {
        Set<String> normalized = symbols.stream().map(QuoteService::normalize)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<Instrument> found = instrumentRepository.findAllById(normalized);
        if (found.size() != normalized.size()) {
            Set<String> known = found.stream().map(Instrument::getSymbol).collect(Collectors.toSet());
            normalized.removeAll(known);
            throw new ApiException(org.springframework.http.HttpStatus.NOT_FOUND, "UNKNOWN_SYMBOL",
                    "Unknown symbol(s): " + String.join(", ", normalized));
        }
        // Keep the caller's order (e.g. the order symbols were added to a watchlist).
        Map<String, Instrument> bySymbol = found.stream()
                .collect(Collectors.toMap(Instrument::getSymbol, i -> i));
        return normalized.stream().map(bySymbol::get).toList();
    }

    public static String normalize(String symbol) {
        return symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
    }
}
