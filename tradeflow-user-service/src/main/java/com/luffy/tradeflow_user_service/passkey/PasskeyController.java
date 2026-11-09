package com.luffy.tradeflow_user_service.passkey;

import com.luffy.tradeflow.common.security.AuthenticatedUser;
import com.luffy.tradeflow_user_service.dto.AuthDtos.TokenResponse;
import com.luffy.tradeflow_user_service.dto.AuthDtos.UserResponse;
import com.luffy.tradeflow_user_service.passkey.PasskeyService.Options;
import com.luffy.tradeflow_user_service.passkey.PasskeyService.PasskeyView;
import com.luffy.tradeflow_user_service.security.ClientInfo;
import com.luffy.tradeflow_user_service.web.RefreshCookies;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth/passkeys")
@Tag(name = "Passkeys")
public class PasskeyController {

    public record Reauthentication(@NotBlank String password, @NotBlank @Size(min = 6, max = 6) String code) {
        @Override
        public String toString() {
            return "Reauthentication[redacted]";
        }
    }

    public record RegisterRequest(@NotNull UUID challengeId, @NotBlank @Size(max = 60) String name,
                                  @NotBlank @Size(max = 8192) String clientDataJSON,
                                  @NotBlank @Size(max = 16384) String attestationObject) {
    }

    public record LoginRequest(@NotNull UUID challengeId, @NotBlank @Size(max = 1024) String credentialId,
                               @NotBlank @Size(max = 8192) String clientDataJSON,
                               @NotBlank @Size(max = 8192) String authenticatorData,
                               @NotBlank @Size(max = 8192) String signature,
                               @NotBlank @Size(max = 256) String userHandle) {
    }

    private final PasskeyService passkeys;
    private final RefreshCookies cookies;
    private final Clock clock;

    public PasskeyController(PasskeyService passkeys, RefreshCookies cookies, Clock clock) {
        this.passkeys = passkeys;
        this.cookies = cookies;
        this.clock = clock;
    }

    @GetMapping
    @SecurityRequirement(name = "bearer")
    @Operation(summary = "Your passkeys")
    public List<PasskeyView> list(AuthenticatedUser user) {
        return passkeys.list(user.id());
    }

    @PostMapping("/register/options")
    @SecurityRequirement(name = "bearer")
    @Operation(summary = "Start adding a passkey (password and a current TOTP code required)")
    public Options registerOptions(AuthenticatedUser user, @Valid @RequestBody Reauthentication request, HttpServletRequest http) {
        return passkeys.registrationOptions(user.id(), request.password(), request.code(), ClientInfo.of(http));
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @SecurityRequirement(name = "bearer")
    @Operation(summary = "Finish adding a passkey")
    public PasskeyView register(AuthenticatedUser user, @Valid @RequestBody RegisterRequest request, HttpServletRequest http) {
        return passkeys.register(user.id(), request.challengeId(), request.name(), request.clientDataJSON(),
                request.attestationObject(), ClientInfo.of(http));
    }

    @PostMapping("/{id}/remove")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @SecurityRequirement(name = "bearer")
    @Operation(summary = "Remove a passkey (password and a current TOTP code required)")
    public void remove(AuthenticatedUser user, @PathVariable UUID id, @Valid @RequestBody Reauthentication request, HttpServletRequest http) {
        passkeys.remove(user.id(), id, request.password(), request.code(), ClientInfo.of(http));
    }

    @PostMapping("/login/options")
    @Operation(summary = "Start signing in with a passkey")
    public Options loginOptions() {
        return passkeys.loginOptions();
    }

    @PostMapping("/login")
    @Operation(summary = "Finish signing in with a passkey")
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        var session = passkeys.login(request.challengeId(), request.credentialId(), request.clientDataJSON(),
                request.authenticatorData(), request.signature(), request.userHandle(), ClientInfo.of(http));
        long expiresIn = Duration.between(clock.instant(), session.access().expiresAt()).getSeconds();
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookies.set(session.refreshToken(), session.refreshExpiresAt()))
                .body(new TokenResponse(session.access().token(), "Bearer", expiresIn, UserResponse.from(session.user())));
    }
}
