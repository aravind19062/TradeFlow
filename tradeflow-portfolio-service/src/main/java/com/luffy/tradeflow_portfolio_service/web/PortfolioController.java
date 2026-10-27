package com.luffy.tradeflow_portfolio_service.web;

import com.luffy.tradeflow.common.security.AuthenticatedUser;
import com.luffy.tradeflow.common.web.PageResponse;
import com.luffy.tradeflow_portfolio_service.domain.LedgerEntry;
import com.luffy.tradeflow_portfolio_service.service.PortfolioQueryService;
import com.luffy.tradeflow_portfolio_service.service.ReconciliationService;
import com.luffy.tradeflow_portfolio_service.web.PortfolioViews.HoldingView;
import com.luffy.tradeflow_portfolio_service.web.PortfolioViews.LedgerEntryView;
import com.luffy.tradeflow_portfolio_service.web.PortfolioViews.PerformanceReport;
import com.luffy.tradeflow_portfolio_service.web.PortfolioViews.PortfolioSummary;
import com.luffy.tradeflow_portfolio_service.web.PortfolioViews.ReconciliationReport;
import com.luffy.tradeflow_portfolio_service.web.PortfolioViews.RiskReport;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Every endpoint is scoped to the caller's own account (user id comes from the JWT). */
@RestController
@RequestMapping("/api/portfolio")
@Tag(name = "Portfolio")
public class PortfolioController {

    private final PortfolioQueryService queryService;
    private final ReconciliationService reconciliationService;

    public PortfolioController(PortfolioQueryService queryService, ReconciliationService reconciliationService) {
        this.queryService = queryService;
        this.reconciliationService = reconciliationService;
    }

    @GetMapping("/summary")
    @Operation(summary = "Cash, holdings at simulated market prices, realized and unrealized P&L")
    public PortfolioSummary summary(AuthenticatedUser user) {
        return queryService.summary(user.id());
    }

    @GetMapping("/holdings")
    @Operation(summary = "Open positions")
    public List<HoldingView> holdings(AuthenticatedUser user) {
        return queryService.summary(user.id()).holdings();
    }

    @GetMapping("/performance")
    @Operation(summary = "Portfolio value over time, rebuilt from the ledger and simulated prices")
    public PerformanceReport performance(AuthenticatedUser user) {
        return queryService.performance(user.id());
    }

    @GetMapping("/risk")
    @Operation(summary = "Allocation, concentration (HHI), max drawdown and warnings")
    public RiskReport risk(AuthenticatedUser user) {
        return queryService.risk(user.id());
    }

    @GetMapping("/transactions")
    @Operation(summary = "Ledger entries (deposits, buys, sells), newest first")
    public PageResponse<LedgerEntryView> transactions(AuthenticatedUser user,
                                                      @RequestParam(required = false) LedgerEntry.Type type,
                                                      @RequestParam(defaultValue = "0") int page,
                                                      @RequestParam(defaultValue = "20") int size) {
        return PageResponse.of(queryService.transactions(user.id(), type, page, size), LedgerEntryView::from);
    }

    @GetMapping("/reconciliation")
    @Operation(summary = "Re-derive my balances from reservations and the ledger and report mismatches")
    public ReconciliationReport reconciliation(AuthenticatedUser user) {
        return reconciliationService.check(user.id());
    }
}
