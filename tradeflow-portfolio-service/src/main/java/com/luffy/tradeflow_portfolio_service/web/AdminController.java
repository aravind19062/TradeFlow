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
    private final com.luffy.tradeflow_portfolio_service.service.CrossServiceReconciliationService crossService;

    public AdminController(ReconciliationService reconciliationService,
                           com.luffy.tradeflow_portfolio_service.service.CrossServiceReconciliationService crossService) {
        this.reconciliationService = reconciliationService;
        this.crossService = crossService;
    }

    @GetMapping("/api/admin/reconciliation/cross-service")
    @Operation(summary = "ADMIN: compare stale reservations with order-service now (auto-releases leaked ones)")
    public com.luffy.tradeflow_portfolio_service.service.CrossServiceReconciliationService.CrossServiceReport crossService() {
        return crossService.run();
    }

    @GetMapping("/api/admin/reconciliation")
    @Operation(summary = "ADMIN: reconcile every account now")
    public ReconciliationReport reconcileAll() {
        return reconciliationService.checkAll();
    }
}
