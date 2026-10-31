package com.luffy.tradeflow_user_service.controller;

import com.luffy.tradeflow.common.web.ApiException;
import com.luffy.tradeflow_user_service.repository.UserRepository;
import com.luffy.tradeflow_user_service.security.MfaService;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * For live-trading-service (SERVICE token only; never routed by the gateway): whether a user's
 * tokens are still current (token version, account active) and whether MFA is enabled. Lets the
 * live service reject a step-up token issued before "sign out everywhere" or an MFA change.
 */
@Hidden
@RestController
public class InternalSessionController {

    public record SessionState(long userId, boolean active, int tokenVersion, boolean mfaEnabled) {
    }

    private final UserRepository users;
    private final MfaService mfa;

    public InternalSessionController(UserRepository users, MfaService mfa) {
        this.users = users;
        this.mfa = mfa;
    }

    @GetMapping("/internal/users/{userId}/session-state")
    @Transactional(readOnly = true)
    public SessionState state(@PathVariable long userId) {
        var user = users.findById(userId).orElseThrow(() -> ApiException.notFound("User " + userId));
        return new SessionState(userId, user.isActive(), user.getTokenVersion(), mfa.enabled(userId));
    }
}
