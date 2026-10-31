package com.luffy.tradeflow_user_service.controller;

import com.luffy.tradeflow.common.security.AuthenticatedUser;
import com.luffy.tradeflow.common.web.ApiException;
import com.luffy.tradeflow_user_service.dto.AuthDtos.LoginRequest;
import com.luffy.tradeflow_user_service.dto.AuthDtos.LoginResponse;
import com.luffy.tradeflow_user_service.dto.AuthDtos.MfaLoginRequest;
import com.luffy.tradeflow_user_service.dto.AuthDtos.StepUpRequest;
import com.luffy.tradeflow_user_service.dto.AuthDtos.StepUpResponse;
import com.luffy.tradeflow_user_service.dto.AuthDtos.TokenResponse;
import com.luffy.tradeflow_user_service.dto.AuthDtos.UserResponse;
import com.luffy.tradeflow_user_service.dto.RegisterRequest;
import com.luffy.tradeflow_user_service.security.ClientInfo;
import com.luffy.tradeflow_user_service.security.SecurityEvent;
import com.luffy.tradeflow_user_service.security.SecurityEventService;
import com.luffy.tradeflow_user_service.security.SessionService;
import com.luffy.tradeflow_user_service.service.AuthService;
import com.luffy.tradeflow_user_service.service.UserService;
import com.luffy.tradeflow_user_service.web.RefreshCookies;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Duration;

@RestController
@Tag(name = "Authentication")
public class AuthController {

    private final UserService userService;
    private final AuthService authService;
    private final SessionService sessions;
    private final RefreshCookies cookies;
    private final SecurityEventService events;
    private final Clock clock;

    public AuthController(UserService userService, AuthService authService, SessionService sessions,
                          RefreshCookies cookies, SecurityEventService events, Clock clock) {
        this.events = events;
        this.userService = userService;
        this.authService = authService;
        this.sessions = sessions;
        this.cookies = cookies;
        this.clock = clock;
    }

    @PostMapping("/api/auth/register")
    @Operation(summary = "Register a user and start a session; a cash account is opened asynchronously")
    public ResponseEntity<TokenResponse> register(@Valid @RequestBody RegisterRequest request, HttpServletRequest http) {
        var session = sessions.start(userService.register(request), java.util.List.of("pwd"), ClientInfo.of(http));
        return withSession(HttpStatus.CREATED, session);
    }

    @PostMapping("/api/auth/login")
    @Operation(summary = "Sign in with email and password; returns tokens, or an MFA challenge for 2FA accounts")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        AuthService.LoginResult result = authService.login(request, ClientInfo.of(http));
        if (result instanceof AuthService.MfaRequired m) {
            return ResponseEntity.ok(new LoginResponse(true, null, null, null, null, m.mfaToken(), m.expiresInSeconds()));
        }
        var session = ((AuthService.Authenticated) result).session();
        TokenResponse t = body(session);
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookies.set(session.refreshToken(), session.refreshExpiresAt()))
                .body(new LoginResponse(false, t.accessToken(), t.tokenType(), t.expiresInSeconds(), t.user(), null, null));
    }

    @PostMapping("/api/auth/login/mfa")
    @Operation(summary = "Complete an MFA sign-in with a TOTP code or a one-time recovery code")
    public ResponseEntity<TokenResponse> loginMfa(@Valid @RequestBody MfaLoginRequest request, HttpServletRequest http) {
        var done = authService.completeMfaLogin(request.mfaToken(), request.code(), request.recoveryCode(),
                ClientInfo.of(http));
        return withSession(HttpStatus.OK, done.session());
    }

    @PostMapping("/api/auth/refresh")
    @Operation(summary = "Exchange the refresh cookie for a new access token (the cookie is rotated)")
    public ResponseEntity<?> refresh(HttpServletRequest http) {
        RefreshCookies.requireClientHeader(http);
        try {
            return withSession(HttpStatus.OK, sessions.refresh(RefreshCookies.read(http), ClientInfo.of(http)));
        } catch (ApiException e) {
            // Clear a dead cookie so the browser stops presenting it.
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(e.getStatus(), e.getMessage());
            problem.setProperty("code", e.getCode());
            return ResponseEntity.status(e.getStatus()).header(HttpHeaders.SET_COOKIE, cookies.clear()).body(problem);
        }
    }

    @PostMapping("/api/auth/logout")
    @Operation(summary = "End this session (revokes its refresh token) and clear the cookie")
    public ResponseEntity<Void> logout(HttpServletRequest http) {
        RefreshCookies.requireClientHeader(http);
        sessions.logout(RefreshCookies.read(http), ClientInfo.of(http));
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookies.clear()).build();
    }

    @PostMapping("/api/auth/logout-all")
    @SecurityRequirement(name = "bearer")
    @Operation(summary = "End every session of this account and invalidate its access tokens")
    public ResponseEntity<Void> logoutAll(AuthenticatedUser user, HttpServletRequest http) {
        sessions.endAllSessions(user.id(), "SIGNED_OUT_EVERYWHERE");
        events.record(user.id(), SecurityEvent.Type.SIGNED_OUT_EVERYWHERE, null, null, ClientInfo.of(http));
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookies.clear()).build();
    }

    @PostMapping("/api/auth/step-up")
    @SecurityRequirement(name = "bearer")
    @Operation(summary = "Re-authenticate (password + TOTP) for a 10-minute live-trading token")
    public StepUpResponse stepUp(AuthenticatedUser user, @AuthenticationPrincipal Jwt jwt,
                                 @Valid @RequestBody StepUpRequest request, HttpServletRequest http) {
        Number tv = jwt.getClaim("tv");
        String sid = jwt.getClaimAsString("sid");
        if (tv == null || sid == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "SESSION_EXPIRED",
                    "Sign in again to continue");
        }
        var stepUp = authService.stepUp(user, tv.intValue(), sid, request.password(), request.code(), ClientInfo.of(http));
        return new StepUpResponse(stepUp.token().token(), "Bearer",
                Duration.between(clock.instant(), stepUp.token().expiresAt()).getSeconds());
    }

    @GetMapping("/api/users/me")
    @SecurityRequirement(name = "bearer")
    @Operation(summary = "The authenticated user's profile")
    public UserResponse me(AuthenticatedUser user) {
        return UserResponse.from(userService.getById(user.id()));
    }

    private ResponseEntity<TokenResponse> withSession(HttpStatus status, SessionService.Session session) {
        return ResponseEntity.status(status)
                .header(HttpHeaders.SET_COOKIE, cookies.set(session.refreshToken(), session.refreshExpiresAt()))
                .body(body(session));
    }

    private TokenResponse body(SessionService.Session session) {
        long expiresIn = Duration.between(clock.instant(), session.access().expiresAt()).getSeconds();
        return new TokenResponse(session.access().token(), "Bearer", expiresIn, UserResponse.from(session.user()));
    }
}
