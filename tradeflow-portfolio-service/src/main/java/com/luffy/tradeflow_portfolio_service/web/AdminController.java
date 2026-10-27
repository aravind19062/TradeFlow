package com.luffy.tradeflow_portfolio_service.web;

import com.luffy.tradeflow_portfolio_service.service.ReconciliationService;
import com.luffy.tradeflow_portfolio_service.web.PortfolioViews.ReconciliationReport;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Admin")
public class AdminController {

    private final ReconciliationService reconciliationService;

    public AdminController(ReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    @GetMapping("/api/admin/reconciliation")
    @Operation(summary = "ADMIN: reconcile every account now")
    public ReconciliationReport reconcileAll() {
        return reconciliationService.checkAll();
    }
}
