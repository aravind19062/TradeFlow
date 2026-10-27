package com.luffy.tradeflow_market_data_service.web;

import com.luffy.tradeflow.common.security.AuthenticatedUser;
import com.luffy.tradeflow_market_data_service.domain.PriceAlert;
import com.luffy.tradeflow_market_data_service.service.AlertService;
import com.luffy.tradeflow_market_data_service.web.MarketDtos.AlertRequest;
import com.luffy.tradeflow_market_data_service.web.MarketDtos.AlertResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/alerts")
@Tag(name = "Price alerts")
public class AlertController {

    private final AlertService alertService;

    public AlertController(AlertService alertService) {
        this.alertService = alertService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a price alert, evaluated on every simulated tick")
    public AlertResponse create(AuthenticatedUser user, @Valid @RequestBody AlertRequest request) {
        return AlertResponse.from(alertService.create(user.id(), request));
    }

    @GetMapping
    @Operation(summary = "My alerts, optionally filtered by status")
    public List<AlertResponse> list(AuthenticatedUser user, @RequestParam(required = false) PriceAlert.Status status) {
        return alertService.list(user.id(), status).stream().map(AlertResponse::from).toList();
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Cancel an active alert")
    public AlertResponse cancel(AuthenticatedUser user, @PathVariable UUID id) {
        return AlertResponse.from(alertService.cancel(user.id(), id));
    }
}
