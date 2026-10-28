package com.luffy.tradeflow_user_service.controller;

import com.luffy.tradeflow.common.security.AuthenticatedUser;
import com.luffy.tradeflow_user_service.dto.AuthDtos.LoginRequest;
import com.luffy.tradeflow_user_service.dto.AuthDtos.TokenResponse;
import com.luffy.tradeflow_user_service.dto.AuthDtos.UserResponse;
import com.luffy.tradeflow_user_service.dto.RegisterRequest;
import com.luffy.tradeflow_user_service.service.AuthService;
import com.luffy.tradeflow_user_service.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Authentication")
public class AuthController {

    private final UserService userService;
    private final AuthService authService;

    public AuthController(UserService userService, AuthService authService) {
        this.userService = userService;
        this.authService = authService;
    }

    @PostMapping("/api/auth/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Register a user; a cash account is opened asynchronously by portfolio-service")
    public TokenResponse register(@Valid @RequestBody RegisterRequest request) {
        return authService.issueToken(userService.register(request));
    }

    @PostMapping("/api/auth/login")
    @Operation(summary = "Exchange email and password for a JWT access token")
    public TokenResponse login(@Valid @RequestBody LoginRequest request,
                               jakarta.servlet.http.HttpServletRequest httpRequest) {
        return authService.login(request, httpRequest.getRemoteAddr());
    }

    @GetMapping("/api/users/me")
    @SecurityRequirement(name = "bearer")
    @Operation(summary = "The authenticated user's profile")
    public UserResponse me(AuthenticatedUser user) {
        return UserResponse.from(userService.getById(user.id()));
    }
}
