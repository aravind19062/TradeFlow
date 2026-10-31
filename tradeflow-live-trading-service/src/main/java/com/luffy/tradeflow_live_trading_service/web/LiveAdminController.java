package com.luffy.tradeflow_live_trading_service.web;

import com.luffy.tradeflow_live_trading_service.access.KillSwitch;
import com.luffy.tradeflow_live_trading_service.access.KillSwitchService;
import com.luffy.tradeflow_live_trading_service.access.LiveAccessGuard;
import com.luffy.tradeflow_live_trading_service.access.LiveAction;
import com.luffy.tradeflow_live_trading_service.audit.LiveAuditEvent;
import com.luffy.tradeflow_live_trading_service.audit.LiveAuditService;
import com.luffy.tradeflow_live_trading_service.connection.BrokerConnectionService;
import com.luffy.tradeflow_live_trading_service.orders.LiveOrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * ADMIN: kill switches and the live audit trail. Halting blocks new orders only; administrators
 * have no endpoint that places or cancels orders on a user's behalf. Changing a kill switch needs
 * the administrator's own step-up token (password + authenticator code, recent), checked here.
 */
@RestController
@RequestMapping("/api/admin/live")
@Tag(name = "Admin: live trading")
public class LiveAdminController {

    public record KillSwitchRequest(boolean enabled, @NotBlank @Size(min = 10, max = 500) String reason) {
    }

    public record AuditItem(int seq, String action, String outcome, String detail, long actorId, Instant occurredAt) {
        static AuditItem of(LiveAuditEvent e) {
            return new AuditItem(e.getSeq(), e.getAction(), e.getOutcome(), e.getDetail(), e.getActorId(), e.getOccurredAt());
        }
    }

    public record UserLive(long userId, KillSwitchService.View killSwitch, List<BrokerConnectionService.View> connections,
                           List<LiveOrderService.View> orders, List<AuditItem> audit, LiveAuditService.ChainCheck chain) {
    }

    private final KillSwitchService killSwitches;
    private final LiveAuditService audit;
    private final BrokerConnectionService connections;
    private final LiveOrderService orders;
    private final LiveAccessGuard guard;

    public LiveAdminController(KillSwitchService killSwitches, LiveAuditService audit,
                               BrokerConnectionService connections, LiveOrderService orders, LiveAccessGuard guard) {
        this.guard = guard;
        this.killSwitches = killSwitches;
        this.audit = audit;
        this.connections = connections;
        this.orders = orders;
    }

    @GetMapping("/kill-switch")
    public KillSwitchService.View global() {
        return killSwitches.global();
    }

    @PutMapping("/kill-switch")
    @Operation(summary = "ADMIN (step-up token): halt or resume new live orders platform-wide (reason required, audited)")
    public KillSwitchService.View setGlobal(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody KillSwitchRequest request) {
        long adminId = guard.authorizeAdmin(jwt, LiveAction.CHANGE_KILL_SWITCH, LiveAuditService.PLATFORM);
        return killSwitches.set(KillSwitch.Scope.GLOBAL, 0, request.enabled(), request.reason(), adminId,
                LiveAccessGuard.authSummary(jwt));
    }

    @PutMapping("/users/{userId}/kill-switch")
    @Operation(summary = "ADMIN (step-up token): halt or resume new live orders for one user (reason required, audited, user notified)")
    public KillSwitchService.View setUser(@AuthenticationPrincipal Jwt jwt, @PathVariable long userId,
                                          @Valid @RequestBody KillSwitchRequest request) {
        long adminId = guard.authorizeAdmin(jwt, LiveAction.CHANGE_KILL_SWITCH, userId);
        return killSwitches.set(KillSwitch.Scope.USER, userId, request.enabled(), request.reason(), adminId,
                LiveAccessGuard.authSummary(jwt));
    }

    @GetMapping("/users/{userId}")
    @Operation(summary = "ADMIN: a user's live connections, orders and verified audit trail")
    public UserLive user(@PathVariable long userId) {
        return new UserLive(userId, killSwitches.forUser(userId), connections.history(userId), orders.list(userId),
                audit.recent(userId, 200).stream().map(AuditItem::of).toList(), audit.verify(userId));
    }
}
