package com.luffy.tradeflow_portfolio_service.service;

import com.luffy.tradeflow.common.web.ApiException;
import com.luffy.tradeflow_portfolio_service.analytics.PerformanceCalculator;
import com.luffy.tradeflow_portfolio_service.analytics.PortfolioAnalytics;
import com.luffy.tradeflow_portfolio_service.client.MarketDataClient;
import com.luffy.tradeflow_portfolio_service.client.MarketDataClient.PricePoint;
import com.luffy.tradeflow_portfolio_service.client.MarketDataClient.Quote;
import com.luffy.tradeflow_portfolio_service.config.PortfolioProperties;
import com.luffy.tradeflow_portfolio_service.domain.Account;
import com.luffy.tradeflow_portfolio_service.domain.Holding;
import com.luffy.tradeflow_portfolio_service.domain.LedgerEntry;
import com.luffy.tradeflow_portfolio_service.repository.AccountRepository;
import com.luffy.tradeflow_portfolio_service.repository.HoldingRepository;
import com.luffy.tradeflow_portfolio_service.repository.LedgerEntryRepository;
import com.luffy.tradeflow_portfolio_service.web.PortfolioViews.PerformanceReport;
import com.luffy.tradeflow_portfolio_service.web.PortfolioViews.PortfolioSummary;
import com.luffy.tradeflow_portfolio_service.web.PortfolioViews.RiskReport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Read side. Database reads are short; market data is fetched over HTTP with
 * timeouts and the summary degrades to cost-basis valuation if it is unavailable.
 */
@Slf4j
@Service
public class PortfolioQueryService {

    public static final int MAX_PERFORMANCE_POINTS = 300;
    private static final int HISTORY_LIMIT = 1000;

    private final AccountRepository accountRepository;
    private final HoldingRepository holdingRepository;
    private final LedgerEntryRepository ledgerRepository;
    private final MarketDataClient marketDataClient;
    private final PortfolioProperties properties;

    public PortfolioQueryService(AccountRepository accountRepository, HoldingRepository holdingRepository,
                                 LedgerEntryRepository ledgerRepository, MarketDataClient marketDataClient,
                                 PortfolioProperties properties) {
        this.accountRepository = accountRepository;
        this.holdingRepository = holdingRepository;
        this.ledgerRepository = ledgerRepository;
        this.marketDataClient = marketDataClient;
        this.properties = properties;
    }

    public PortfolioSummary summary(long userId) {
        Account account = account(userId);
        List<Holding> holdings = holdingRepository.findByUserIdOrderBySymbolAsc(userId);
        List<String> symbols = holdings.stream().filter(h -> h.getQuantity() > 0).map(Holding::getSymbol).toList();
        Map<String, Quote> quotes = new HashMap<>();
        boolean pricesAvailable = true;
        try {
            quotes = marketDataClient.quotes(symbols).stream()
                    .collect(Collectors.toMap(Quote::symbol, Function.identity()));
        } catch (ApiException e) {
            log.warn("Valuing portfolio of user {} at cost: {}", userId, e.getMessage());
            pricesAvailable = false;
        }
        return PortfolioAnalytics.summarize(account, holdings, quotes, pricesAvailable, properties.currency());
    }

    public PerformanceReport performance(long userId) {
        Account account = account(userId);
        List<LedgerEntry> ledger = ledgerRepository.findByUserIdOrderByOccurredAtAscIdAsc(userId);
        TreeSet<String> symbols = ledger.stream().map(LedgerEntry::getSymbol).filter(Objects::nonNull)
                .collect(Collectors.toCollection(TreeSet::new));
        Map<String, List<PricePoint>> history = marketDataClient.histories(symbols, account.getCreatedAt(),
                        HISTORY_LIMIT).entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().points()));
        return PerformanceCalculator.replay(account.getCreatedAt(), ledger, history, MAX_PERFORMANCE_POINTS);
    }

    public RiskReport risk(long userId) {
        PortfolioSummary summary = summary(userId);
        PerformanceReport performance = null;
        try {
            performance = performance(userId);
        } catch (ApiException e) {
            log.warn("Risk report without drawdown for user {}: {}", userId, e.getMessage());
        }
        return PortfolioAnalytics.risk(summary, performance == null ? null : performance.maxDrawdownRatio());
    }

    public Page<LedgerEntry> transactions(long userId, LedgerEntry.Type type, int page, int size) {
        account(userId);
        if (page < 0 || size < 1 || size > 100) {
            throw ApiException.badRequest("INVALID_PAGE", "page must be >= 0 and size between 1 and 100");
        }
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Order.desc("occurredAt"), Sort.Order.desc("id")));
        return type == null
                ? ledgerRepository.findByUserId(userId, pageable)
                : ledgerRepository.findByUserIdAndType(userId, type, pageable);
    }

    private Account account(long userId) {
        return accountRepository.findById(userId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                "ACCOUNT_NOT_FOUND", "No portfolio account yet; it is opened shortly after registration"));
    }
}
