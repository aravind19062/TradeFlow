package com.luffy.tradeflow_user_service.controller;

import com.luffy.tradeflow.common.security.AuthenticatedUser;
import com.luffy.tradeflow_user_service.security.ClientInfo;
import com.luffy.tradeflow_user_service.security.MfaService;
import com.luffy.tradeflow_user_service.security.SecurityEvent;
import com.luffy.tradeflow_user_service.security.SecurityEventService;
import com.luffy.tradeflow_user_service.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/** Two-factor authentication (TOTP) management for the signed-in user. */
@RestController
@RequestMapping("/api/auth/mfa")
@SecurityRequirement(name = "bearer")
@Tag(name = "Two-factor authentication")
public class MfaController {

    public record PasswordRequest(@NotBlank String password) {
    }

    public record CodeRequest(@NotBlank @Size(min = 6, max = 6) String code) {
    }

    public record PasswordAndCode(@NotBlank String password, @Size(max = 6) String code, @Size(max = 20) String recoveryCode) {
    }

    /** The seed is returned exactly once, at enrolment. */
    public record EnrollmentResponse(String secret, String otpauthUri) {
    }

    /** Shown once; store them offline. Each can be used a single time. */
    public record RecoveryCodesResponse(List<String> recoveryCodes) {
    }

    public record SecurityEventView(String type, String detail, String clientIp, String userAgent, Instant occurredAt,
                                    boolean byAdministrator) {
        static SecurityEventView from(SecurityEvent e) {
            return new SecurityEventView(e.getType(), visibleDetail(e), e.getClientIp(), e.getUserAgent(), e.getOccurredAt(),
                    e.getActorId() != null && e.getActorId() != e.getUserId());
        }

        /** Details can hold internal notes, so only these known-safe ones reach the user. */
        private static String visibleDetail(SecurityEvent e) {
            return switch (e.getType()) {
                case "MFA_RESET_BY_ADMIN" -> "Reset by an administrator";
                case "BANK_DETAILS_SAVED", "PASSKEY_ADDED", "PASSKEY_REMOVED" -> e.getDetail();
                case "LOGIN_SUCCEEDED", "LOGIN_FAILED" -> "passkey".equals(e.getDetail()) ? "with a passkey" : null;
                default -> null;
            };
        }
    }

    private final MfaService mfa;
    private final UserService users;
    private final SecurityEventService events;

    public MfaController(MfaService mfa, UserService users, SecurityEventService events) {
        this.mfa = mfa;
        this.users = users;
        this.events = events;
    }

    @GetMapping
    @Operation(summary = "Two-factor status and remaining recovery codes")
    public MfaService.Status status(AuthenticatedUser user) {
        return mfa.status(user.id());
    }

    @PostMapping("/totp/enroll")
    @Operation(summary = "Start TOTP enrolment (password required); returns the seed once")
    public EnrollmentResponse enroll(AuthenticatedUser user, @Valid @RequestBody PasswordRequest request,
                                     HttpServletRequest http) {
        MfaService.Enrollment e = mfa.beginEnrollment(users.getById(user.id()), request.password(), ClientInfo.of(http));
        return new EnrollmentResponse(e.secret(), e.otpauthUri());
    }

    @PostMapping("/totp/confirm")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Confirm enrolment with a code from the authenticator; returns recovery codes once")
    public RecoveryCodesResponse confirm(AuthenticatedUser user, @Valid @RequestBody CodeRequest request,
                                         HttpServletRequest http) {
        return new RecoveryCodesResponse(mfa.confirmEnrollment(users.getById(user.id()), request.code(), ClientInfo.of(http)));
    }

    @PostMapping("/recovery-codes")
    @Operation(summary = "Replace all recovery codes (password and a current TOTP code required)")
    public RecoveryCodesResponse regenerate(AuthenticatedUser user, @Valid @RequestBody PasswordAndCode request,
                                            HttpServletRequest http) {
        return new RecoveryCodesResponse(mfa.regenerateRecoveryCodes(users.getById(user.id()), request.password(),
                request.code(), ClientInfo.of(http)));
    }

    @PostMapping("/totp/disable")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Turn off two-factor authentication (password and a second factor); ends all sessions")
    public void disable(AuthenticatedUser user, @Valid @RequestBody PasswordAndCode request, HttpServletRequest http) {
        mfa.disable(users.getById(user.id()), request.password(), request.code(), request.recoveryCode(), ClientInfo.of(http));
    }

    @GetMapping("/security-events")
    @Operation(summary = "Recent security activity on this account")
    public List<SecurityEventView> securityEvents(AuthenticatedUser user) {
        return events.recent(user.id(), 50).stream().map(SecurityEventView::from).toList();
    }
}
