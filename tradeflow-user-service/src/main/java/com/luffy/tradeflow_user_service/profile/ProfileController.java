package com.luffy.tradeflow_user_service.profile;

import com.luffy.tradeflow.common.security.AuthenticatedUser;
import com.luffy.tradeflow_user_service.dto.AuthDtos.UserResponse;
import com.luffy.tradeflow_user_service.profile.ProfileDtos.BankAccountRequest;
import com.luffy.tradeflow_user_service.profile.ProfileDtos.BankAccountResponse;
import com.luffy.tradeflow_user_service.profile.ProfileDtos.BankAccountView;
import com.luffy.tradeflow_user_service.profile.ProfileDtos.ProfileUpdateRequest;
import com.luffy.tradeflow_user_service.profile.ProfileDtos.ReauthenticationRequest;
import com.luffy.tradeflow_user_service.security.ClientInfo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users/me")
@SecurityRequirement(name = "bearer")
@Tag(name = "Profile")
public class ProfileController {

    private final ProfileService profiles;

    public ProfileController(ProfileService profiles) {
        this.profiles = profiles;
    }

    @PutMapping("/profile")
    @Operation(summary = "Update your personal details (email and role cannot be changed here)")
    public UserResponse update(AuthenticatedUser user, @Valid @RequestBody ProfileUpdateRequest request,
                               HttpServletRequest http) {
        return UserResponse.from(profiles.updateProfile(user.id(), request, ClientInfo.of(http)));
    }

    @GetMapping("/bank-account")
    @Operation(summary = "Your bank account, with the number masked; account is null when none is saved")
    public BankAccountResponse bankAccount(AuthenticatedUser user) {
        return new BankAccountResponse(profiles.bankAccount(user.id()).orElse(null));
    }

    @PutMapping("/bank-account")
    @Operation(summary = "Add or replace your bank account (password and a current TOTP code required)")
    public BankAccountView saveBankAccount(AuthenticatedUser user, @Valid @RequestBody BankAccountRequest request,
                                           HttpServletRequest http) {
        return profiles.saveBankAccount(user.id(), request, ClientInfo.of(http));
    }

    @PostMapping("/bank-account/remove")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remove your bank account (password and a current TOTP code required)")
    public void removeBankAccount(AuthenticatedUser user, @Valid @RequestBody ReauthenticationRequest request,
                                  HttpServletRequest http) {
        profiles.removeBankAccount(user.id(), request.password(), request.code(), ClientInfo.of(http));
    }
}
