package com.luffy.tradeflow_market_data_service.web;

import com.luffy.tradeflow_market_data_service.service.MarketSimulationService;
import com.luffy.tradeflow_market_data_service.service.QuoteService;
import com.luffy.tradeflow_market_data_service.web.MarketDtos.HistoryResponse;
import com.luffy.tradeflow_market_data_service.web.MarketDtos.InstrumentResponse;
import com.luffy.tradeflow_market_data_service.web.MarketDtos.QuoteResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Public, read-only market data. Every price is simulated. */
@RestController
@RequestMapping("/api/market")
@Tag(name = "Market data (simulated)")
public class MarketDataController {

    private final QuoteService quoteService;
    private final QuoteStream stream;

    public MarketDataController(QuoteService quoteService, QuoteStream stream) {
        this.quoteService = quoteService;
        this.stream = stream;
    }

    @GetMapping(value = "/stream", produces = org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Server-sent events: a 'tick' event each time new simulated prices exist")
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter stream() {
        var emitter = stream.connect();
        if (emitter == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "Too many live connections");
        }
        return emitter;
    }

    @GetMapping("/instruments")
    @Operation(summary = "Tradable demo instruments")
    public List<InstrumentResponse> instruments() {
        return quoteService.instruments().stream().map(InstrumentResponse::from).toList();
    }

    @GetMapping("/quotes")
    @Operation(summary = "Latest simulated quotes; all instruments unless symbols=A,B is given")
    public List<QuoteResponse> quotes(@RequestParam(required = false) List<String> symbols) {
        return quoteService.quotes(symbols);
    }

    @GetMapping("/quotes/{symbol}")
    @Operation(summary = "Latest simulated quote for one symbol")
    public QuoteResponse quote(@PathVariable String symbol) {
        return quoteService.quote(symbol);
    }

    @GetMapping("/history/{symbol}")
    @Operation(summary = "Simulated price history, oldest first")
    public HistoryResponse history(@PathVariable String symbol,
                                   @RequestParam(required = false)
                                   @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant since,
                                   @RequestParam(defaultValue = "200") int limit) {
        return new HistoryResponse(QuoteService.normalize(symbol), MarketSimulationService.SOURCE,
                quoteService.history(symbol, since, limit));
    }

    @GetMapping("/history")
    @Operation(summary = "Simulated price history for several symbols")
    public Map<String, HistoryResponse> histories(@RequestParam List<String> symbols,
                                                  @RequestParam(required = false)
                                                  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant since,
                                                  @RequestParam(defaultValue = "200") int limit) {
        Map<String, HistoryResponse> result = new LinkedHashMap<>();
        for (String symbol : symbols) {
            result.put(QuoteService.normalize(symbol), history(symbol, since, limit));
        }
        return result;
    }
}
