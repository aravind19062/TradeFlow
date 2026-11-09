package com.luffy.tradeflow_user_service.controller;

import com.luffy.tradeflow.common.security.AuthenticatedUser;
import com.luffy.tradeflow.common.web.ApiException;
import com.luffy.tradeflow_user_service.controller.MfaController.SecurityEventView;
import com.luffy.tradeflow_user_service.security.ClientInfo;
import com.luffy.tradeflow_user_service.security.SecurityEvent.Type;
import com.luffy.tradeflow_user_service.security.SecurityEventService;
import com.luffy.tradeflow_user_service.security.SessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** The signed-in user's devices and full account activity. */
@RestController
@RequestMapping("/api/auth")
@SecurityRequirement(name = "bearer")
@Tag(name = "Sessions and activity")
public class SessionController {

    public record ActivityPage(List<SecurityEventView> items, int page, int totalPages, long totalItems) {
    }

    private static final Map<String, Set<Type>> CATEGORIES = Map.of(
            "signin", EnumSet.of(Type.LOGIN_SUCCEEDED, Type.LOGIN_FAILED, Type.MFA_CHALLENGE_FAILED, Type.RECOVERY_CODE_USED,
                    Type.SIGNED_OUT, Type.SIGNED_OUT_EVERYWHERE, Type.SESSION_REVOKED),
            "security", EnumSet.of(Type.MFA_ENROLLMENT_STARTED, Type.MFA_ENABLED, Type.MFA_DISABLED,
                    Type.RECOVERY_CODES_REGENERATED, Type.MFA_RESET_BY_ADMIN, Type.STEP_UP_SUCCEEDED, Type.STEP_UP_FAILED,
                    Type.REFRESH_REUSE_DETECTED, Type.PASSKEY_ADDED, Type.PASSKEY_REMOVED, Type.PASSKEY_CHANGE_FAILED),
            "profile", EnumSet.of(Type.PROFILE_UPDATED, Type.BANK_DETAILS_SAVED, Type.BANK_DETAILS_REMOVED,
                    Type.BANK_DETAILS_CHANGE_FAILED),
            "failed", EnumSet.of(Type.LOGIN_FAILED, Type.MFA_CHALLENGE_FAILED, Type.STEP_UP_FAILED,
                    Type.BANK_DETAILS_CHANGE_FAILED, Type.REFRESH_REUSE_DETECTED, Type.PASSKEY_CHANGE_FAILED));

    private final SessionService sessions;
    private final SecurityEventService events;

    public SessionController(SessionService sessions, SecurityEventService events) {
        this.sessions = sessions;
        this.events = events;
    }

    @GetMapping("/sessions")
    @Operation(summary = "Devices currently signed in to this account")
    public List<SessionService.ActiveSession> list(AuthenticatedUser user, @AuthenticationPrincipal Jwt jwt) {
        Number tv = jwt.getClaim("tv");
        if (tv == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "SESSION_EXPIRED", "Sign in again to continue");
        }
        return sessions.active(user.id(), tv.intValue(), currentSession(jwt));
    }

    @DeleteMapping("/sessions/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Sign out one other device")
    public void revoke(AuthenticatedUser user, @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, HttpServletRequest http) {
        sessions.revoke(user.id(), id, currentSession(jwt), ClientInfo.of(http));
    }

    @GetMapping("/activity")
    @Operation(summary = "This account's activity, newest first; category is signin, security, profile or failed")
    public ActivityPage activity(AuthenticatedUser user, @RequestParam(required = false) String category,
                                 @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        Set<String> types = null;
        if (category != null && !category.isBlank()) {
            Set<Type> selected = CATEGORIES.get(category);
            if (selected == null) {
                throw ApiException.badRequest("INVALID_CATEGORY",
                        "category must be one of " + Arrays.toString(CATEGORIES.keySet().toArray()));
            }
            types = selected.stream().map(Enum::name).collect(Collectors.toSet());
        }
        var result = events.page(user.id(), types, page, size);
        return new ActivityPage(result.getContent().stream().map(SecurityEventView::from).toList(),
                result.getNumber(), result.getTotalPages(), result.getTotalElements());
    }

    private static UUID currentSession(Jwt jwt) {
        String sid = jwt.getClaimAsString("sid");
        return sid == null ? null : UUID.fromString(sid);
    }
}
