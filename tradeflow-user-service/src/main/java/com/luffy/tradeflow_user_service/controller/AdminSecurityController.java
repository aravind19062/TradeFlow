package com.luffy.tradeflow_user_service.controller;

import com.luffy.tradeflow.common.security.AuthenticatedUser;
import com.luffy.tradeflow_user_service.security.AdminSecurityService;
import com.luffy.tradeflow_user_service.security.ClientInfo;
import com.luffy.tradeflow_user_service.security.SecurityEventService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** ADMIN: audited account recovery (see AdminSecurityService and docs/SECURITY.md). */
@RestController
@Tag(name = "Admin: account security")
public class AdminSecurityController {

    public record MfaResetRequest(@NotBlank @Size(min = 20, max = 300) String reason,
                                  @NotBlank @Size(max = 100) String verificationReference,
                                  @NotBlank @Size(min = 6, max = 6) String adminCode) {
    }

    private final AdminSecurityService service;
    private final SecurityEventService events;

    public AdminSecurityController(AdminSecurityService service, SecurityEventService events) {
        this.service = service;
        this.events = events;
    }

    @PostMapping("/api/admin/users/{userId}/mfa/reset")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "ADMIN: remove a user's 2FA after out-of-band identity verification (audited)")
    public void reset(AuthenticatedUser admin, @PathVariable long userId, @Valid @RequestBody MfaResetRequest request,
                      HttpServletRequest http) {
        service.resetMfa(admin, userId, request.reason(), request.verificationReference(), request.adminCode(),
                ClientInfo.of(http));
    }

    @GetMapping("/api/admin/users/{userId}/security-events")
    @Operation(summary = "ADMIN: a user's security activity")
    public List<MfaController.SecurityEventView> securityEvents(@PathVariable long userId) {
        return events.recent(userId, 100).stream().map(MfaController.SecurityEventView::from).toList();
    }
}
