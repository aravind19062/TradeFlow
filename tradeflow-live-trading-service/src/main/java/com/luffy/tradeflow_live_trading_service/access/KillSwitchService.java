package com.luffy.tradeflow_live_trading_service.access;

import com.luffy.tradeflow.common.web.ApiException;
import com.luffy.tradeflow_live_trading_service.audit.LiveAuditService;
import com.luffy.tradeflow_live_trading_service.notifications.NotificationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * Administrator halt switches. Turning one on blocks new orders (and new broker connections)
 * immediately; it never cancels orders already at the broker, and users can still view and cancel
 * their existing orders.
 */
@Service
public class KillSwitchService {

    public record View(String scope, long userId, boolean enabled, String reason, Long actorId, Instant changedAt) {
    }

    private final KillSwitchRepository repository;
    private final LiveAuditService audit;
    private final NotificationService notifications;
    private final Clock clock;

    public KillSwitchService(KillSwitchRepository repository, LiveAuditService audit, NotificationService notifications,
                             Clock clock) {
        this.repository = repository;
        this.audit = audit;
        this.notifications = notifications;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public View global() {
        return view(KillSwitch.Scope.GLOBAL, 0);
    }

    @Transactional(readOnly = true)
    public View forUser(long userId) {
        return view(KillSwitch.Scope.USER, userId);
    }

    /** The reason a new order would be halted, or null. */
    @Transactional(readOnly = true)
    public String haltReason(long userId) {
        View g = global();
        if (g.enabled()) {
            return "Live trading is halted platform-wide by an administrator.";
        }
        View u = forUser(userId);
        return u.enabled() ? "Live trading is halted for your account by an administrator." : null;
    }

    @Transactional
    public View set(KillSwitch.Scope scope, long userId, boolean enabled, String reason, long actorId) {
        if (reason == null || reason.trim().length() < 10) {
            throw ApiException.badRequest("REASON_REQUIRED", "Give a reason of at least 10 characters");
        }
        long target = scope == KillSwitch.Scope.GLOBAL ? 0 : userId;
        KillSwitch.Key key = new KillSwitch.Key(scope.name(), target);
        KillSwitch k = repository.findById(key).orElseGet(() -> new KillSwitch(key));
        k.set(enabled, reason.trim(), actorId, clock.instant());
        repository.save(k);
        audit.record(scope == KillSwitch.Scope.GLOBAL ? LiveAuditService.PLATFORM : target,
                enabled ? "KILL_SWITCH_ON" : "KILL_SWITCH_OFF", LiveAuditService.Outcome.ALLOWED,
                scope + ": " + reason.trim(), actorId);
        if (scope == KillSwitch.Scope.USER) {
            notifications.notify(target, enabled ? "TRADING_HALTED" : "TRADING_RESUMED", enabled
                    ? "An administrator halted new live orders on your account. Orders already at your broker were "
                    + "not cancelled; you can still view and cancel them."
                    : "An administrator lifted the halt on new live orders for your account.");
        }
        return toView(k);
    }

    private View view(KillSwitch.Scope scope, long userId) {
        return repository.findById(new KillSwitch.Key(scope.name(), userId)).map(KillSwitchService::toView)
                .orElse(new View(scope.name(), userId, false, null, null, null));
    }

    private static View toView(KillSwitch k) {
        return new View(k.getId().scope(), k.getId().userId(), k.isEnabled(), k.getReason(), k.getActorId(), k.getChangedAt());
    }
}
