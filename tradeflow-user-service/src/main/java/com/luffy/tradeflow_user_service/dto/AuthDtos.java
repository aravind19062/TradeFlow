package com.luffy.tradeflow_user_service.dto;

import com.luffy.tradeflow_user_service.entity.User;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.LocalDateTime;

public final class AuthDtos {

    private AuthDtos() {
    }

    public record LoginRequest(@NotBlank @Email String email, @NotBlank String password) {
    }

    /**
     * Result of a successful sign-in, registration or refresh. The refresh token is NOT in the
     * body: it is set as an HttpOnly cookie that scripts cannot read.
     */
    public record TokenResponse(String accessToken, String tokenType, long expiresInSeconds, UserResponse user) {
    }

    /**
     * POST /api/auth/login: either tokens ({@code mfaRequired=false}) or, for accounts with
     * two-factor authentication, an {@code mfaToken} to complete at /api/auth/login/mfa.
     */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record LoginResponse(boolean mfaRequired, String accessToken, String tokenType, Long expiresInSeconds,
                                UserResponse user, String mfaToken, Long mfaExpiresInSeconds) {
    }

    public record MfaLoginRequest(@NotBlank String mfaToken, @Size(max = 10) String code,
                                  @Size(max = 20) String recoveryCode) {
    }

    public record StepUpRequest(@NotBlank String password, @NotBlank @Size(min = 6, max = 6) String code) {
    }

    /** A 10-minute token for live-trading actions (audience tradeflow-live). */
    public record StepUpResponse(String stepUpToken, String tokenType, long expiresInSeconds) {
    }

    /** Public view of a user; never includes the password hash. */
    public record UserResponse(Long id, String name, String email, String role, LocalDate dateOfBirth,
                               String gender, String mobileNumber, String occupation, String maritalStatus,
                               String incomeRange, boolean active, LocalDateTime createdAt) {

        public static UserResponse from(User user) {
            return new UserResponse(user.getId(), user.getName(), user.getMailId(), user.getRole(),
                    user.getDateOfBirth(), user.getGender(), user.getMobileNumber(), user.getOccupation(),
                    user.getMaritalStatus(), user.getIncomeRange(), user.isActive(), user.getCreatedAt());
        }
    }
}
