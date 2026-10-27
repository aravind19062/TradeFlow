package com.luffy.tradeflow_market_data_service.service;

import com.luffy.tradeflow.common.web.ApiException;
import com.luffy.tradeflow_market_data_service.domain.WatchlistItem;
import com.luffy.tradeflow_market_data_service.repository.WatchlistItemRepository;
import com.luffy.tradeflow_market_data_service.web.MarketDtos.QuoteResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;

@Service
public class WatchlistService {

    public static final int MAX_ITEMS = 50;

    private final WatchlistItemRepository repository;
    private final QuoteService quoteService;
    private final Clock clock;

    public WatchlistService(WatchlistItemRepository repository, QuoteService quoteService, Clock clock) {
        this.repository = repository;
        this.quoteService = quoteService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<QuoteResponse> quotes(long userId) {
        List<String> symbols = repository.findByUserIdOrderByAddedAtAsc(userId).stream()
                .map(WatchlistItem::getSymbol).toList();
        return symbols.isEmpty() ? List.of() : quoteService.quotes(symbols);
    }

    /** Idempotent: adding a symbol that is already watched is a no-op. */
    @Transactional
    public void add(long userId, String rawSymbol) {
        String symbol = quoteService.instrument(rawSymbol).getSymbol();
        if (repository.findByUserIdAndSymbol(userId, symbol).isPresent()) {
            return;
        }
        if (repository.countByUserId(userId) >= MAX_ITEMS) {
            throw ApiException.unprocessable("WATCHLIST_FULL", "A watchlist can hold at most " + MAX_ITEMS + " symbols");
        }
        repository.save(new WatchlistItem(userId, symbol, clock.instant()));
    }

    @Transactional
    public void remove(long userId, String rawSymbol) {
        repository.findByUserIdAndSymbol(userId, QuoteService.normalize(rawSymbol)).ifPresent(repository::delete);
    }
}
