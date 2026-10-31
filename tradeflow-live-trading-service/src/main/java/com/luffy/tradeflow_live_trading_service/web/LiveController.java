package com.luffy.tradeflow_live_trading_service.web;

import com.luffy.tradeflow.common.web.ApiException;
import com.luffy.tradeflow_live_trading_service.access.KillSwitchService;
import com.luffy.tradeflow_live_trading_service.access.LiveAccessGuard;
import com.luffy.tradeflow_live_trading_service.access.LiveAction;
import com.luffy.tradeflow_live_trading_service.broker.BrokerAdapter;
import com.luffy.tradeflow_live_trading_service.connection.BrokerConnectionService;
import com.luffy.tradeflow_live_trading_service.connection.CredentialVault;
import com.luffy.tradeflow_live_trading_service.notifications.NotificationService;
import com.luffy.tradeflow_live_trading_service.orders.LiveOrderService;
import com.luffy.tradeflow_live_trading_service.orders.OrderParams;
import com.luffy.tradeflow_live_trading_service.platform.PlatformChecks;
import com.luffy.tradeflow_live_trading_service.risk.RiskLimitService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * LIVE trading API (/api/live/**, labelled LIVE by the gateway). Reads accept an ordinary access
 * token. Every state-changing call requires a step-up token and passes {@link LiveAccessGuard};
 * no endpoint returns broker tokens.
 */
@RestController
@RequestMapping("/api/live")
@Tag(name = "Live trading")
public class LiveController {

    public record Status(boolean brokerConfigured, String broker, EligibilityView eligibility,
                         BrokerConnectionService.View connection, boolean halted, String haltReason,
                         RiskLimitService.View limits, String notice) {
    }

    public record EligibilityView(boolean available, boolean eligible, String state, List<String> reasons,
                                  java.time.Instant expiresAt) {
    }

    public record OrderRequest(String exchange, String symbol, String side, String type, long quantity,
                               BigDecimal limitPrice, BigDecimal triggerPrice) {
        OrderParams params() {
            return new OrderParams(exchange, symbol, side, type, quantity, limitPrice, triggerPrice);
        }
    }

    public record PlaceRequest(@NotNull UUID confirmationId, String clientOrderId, OrderRequest order) {
    }

    public record CallbackRequest(String state, String code) {
    }

    public record LimitsRequest(BigDecimal maxOrderValue, int maxOrdersPerDay, BigDecimal maxDailyTurnover) {
    }

    private final LiveAccessGuard guard;
    private final PlatformChecks platform;
    private final BrokerConnectionService connections;
    private final CredentialVault vault;
    private final BrokerAdapter broker;
    private final LiveOrderService orders;
    private final RiskLimitService limits;
    private final KillSwitchService killSwitches;
    private final NotificationService notifications;

    public LiveController(LiveAccessGuard guard, PlatformChecks platform, BrokerConnectionService connections,
                          CredentialVault vault, BrokerAdapter broker, LiveOrderService orders, RiskLimitService limits,
                          KillSwitchService killSwitches, NotificationService notifications) {
        this.guard = guard;
        this.platform = platform;
        this.connections = connections;
        this.vault = vault;
        this.broker = broker;
        this.orders = orders;
        this.limits = limits;
        this.killSwitches = killSwitches;
        this.notifications = notifications;
    }

    // ------------------------------------------------------------------ status

    @GetMapping("/status")
    @Operation(summary = "Live dashboard: broker configured?, eligibility, connection, halts, limits")
    public Status status(@AuthenticationPrincipal Jwt jwt) {
        long userId = LiveAccessGuard.userId(jwt);
        EligibilityView eligibility;
        try {
            PlatformChecks.Eligibility e = platform.eligibility(userId);
            eligibility = new EligibilityView(true, e.eligible(), e.state(), e.reasons(), e.expiresAt());
        } catch (ApiException unavailable) {
            eligibility = new EligibilityView(false, false, null, List.of("Readiness could not be checked right now"), null);
        }
        String halt = killSwitches.haltReason(userId);
        return new Status(connections.brokerConfigured(), connections.brokerCode(), eligibility,
                connections.current(userId).orElse(null), halt != null, halt, limits.view(userId),
                connections.brokerConfigured() ? null
                        : "No broker is integrated yet. Live trading will open once a broker has been approved.");
    }

    // ------------------------------------------------------------------ broker connection

    @PostMapping("/broker/connect")
    @Operation(summary = "Start linking a broker account (step-up; requires readiness)")
    public BrokerConnectionService.Started connect(@AuthenticationPrincipal Jwt jwt) {
        return connections.start(guard.authorize(jwt, LiveAction.CONNECT_BROKER));
    }

    @PostMapping("/broker/callback")
    @Operation(summary = "Finish linking with the broker's one-time code (step-up; requires readiness)")
    public BrokerConnectionService.View callback(@AuthenticationPrincipal Jwt jwt, @RequestBody CallbackRequest request) {
        return connections.complete(guard.authorize(jwt, LiveAction.COMPLETE_CONNECTION), request.state(), request.code());
    }

    @PostMapping("/broker/disconnect")
    @Operation(summary = "Disconnect and erase broker tokens (step-up). Does not cancel broker orders.")
    public void disconnect(@AuthenticationPrincipal Jwt jwt) {
        connections.disconnect(guard.authorize(jwt, LiveAction.DISCONNECT_BROKER));
    }

    @GetMapping("/broker/funds")
    public BrokerAdapter.Funds funds(@AuthenticationPrincipal Jwt jwt) {
        return brokerRead(jwt, broker::funds);
    }

    @GetMapping("/broker/holdings")
    public List<BrokerAdapter.Holding> holdings(@AuthenticationPrincipal Jwt jwt) {
        return brokerRead(jwt, broker::holdings);
    }

    @GetMapping("/broker/positions")
    public List<BrokerAdapter.Position> positions(@AuthenticationPrincipal Jwt jwt) {
        return brokerRead(jwt, broker::positions);
    }

    @GetMapping("/broker/orders")
    @Operation(summary = "All orders at the broker (always available while connected, even if readiness was revoked)")
    public List<BrokerAdapter.BrokerOrder> brokerOrders(@AuthenticationPrincipal Jwt jwt) {
        return brokerRead(jwt, broker::orders);
    }

    // ------------------------------------------------------------------ orders

    @PostMapping("/orders/preview")
    @Operation(summary = "Check an order against limits and get a one-time confirmation (step-up; requires readiness)")
    public LiveOrderService.Preview preview(@AuthenticationPrincipal Jwt jwt, @RequestBody OrderRequest request) {
        return orders.preview(guard.authorize(jwt, LiveAction.PREVIEW_ORDER), request.params());
    }

    @PostMapping("/orders")
    @Operation(summary = "Place a previewed order at the broker (step-up; requires readiness; confirmation must match)")
    public LiveOrderService.View place(@AuthenticationPrincipal Jwt jwt, @RequestBody PlaceRequest request) {
        if (request.order() == null) {
            throw ApiException.badRequest("INVALID_ORDER", "Order details are required");
        }
        return orders.place(guard.authorize(jwt, LiveAction.PLACE_ORDER), request.confirmationId(),
                request.clientOrderId(), request.order().params());
    }

    @PostMapping("/orders/{orderId}/cancel")
    @Operation(summary = "Cancel an order (step-up). Allowed even after readiness revocation or a halt.")
    public LiveOrderService.View cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable String orderId) {
        return orders.cancel(guard.authorize(jwt, LiveAction.CANCEL_ORDER), orderId);
    }

    @GetMapping("/orders")
    public List<LiveOrderService.View> list(@AuthenticationPrincipal Jwt jwt) {
        return orders.list(LiveAccessGuard.userId(jwt));
    }

    @GetMapping("/orders/{orderId}")
    public LiveOrderService.View get(@AuthenticationPrincipal Jwt jwt, @PathVariable String orderId) {
        return orders.get(LiveAccessGuard.userId(jwt), orderId);
    }

    // ------------------------------------------------------------------ limits, notifications

    @GetMapping("/limits")
    public RiskLimitService.View limits(@AuthenticationPrincipal Jwt jwt) {
        return limits.view(LiveAccessGuard.userId(jwt));
    }

    @PutMapping("/limits")
    @Operation(summary = "Set personal limits (only stricter than the platform's; raising one needs step-up)")
    public RiskLimitService.View setLimits(@AuthenticationPrincipal Jwt jwt, @RequestBody LimitsRequest request) {
        long userId = LiveAccessGuard.userId(jwt);
        var requested = new RiskLimitService.Limits(request.maxOrderValue(), request.maxOrdersPerDay(),
                request.maxDailyTurnover());
        limits.validate(requested);
        if (limits.isLoosening(userId, requested)) {
            guard.authorize(jwt, LiveAction.LOOSEN_LIMITS);
        }
        return limits.update(userId, requested);
    }

    @GetMapping("/notifications")
    public List<NotificationService.View> notifications(@AuthenticationPrincipal Jwt jwt) {
        return notifications.recent(LiveAccessGuard.userId(jwt));
    }

    @PostMapping("/notifications/{id}/read")
    public NotificationService.View read(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return notifications.markRead(LiveAccessGuard.userId(jwt), id);
    }

    private <T> T brokerRead(Jwt jwt, Function<String, T> call) {
        if (!connections.brokerConfigured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "BROKER_NOT_CONFIGURED",
                    "No broker is integrated yet. Live trading will open once a broker has been approved.");
        }
        long userId = guard.authorize(jwt, LiveAction.VIEW_BROKER_DATA);
        return call.apply(vault.accessToken(connections.requireActive(userId).getId()));
    }
}
