package com.luffy.tradeflow_user_service.dto;

import com.luffy.tradeflow_user_service.entity.User;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

import java.time.LocalDate;
import java.time.LocalDateTime;

public final class AuthDtos {

    private AuthDtos() {
    }

    public record LoginRequest(@NotBlank @Email String email, @NotBlank String password) {
    }

    public record TokenResponse(String accessToken, String tokenType, long expiresInSeconds, UserResponse user) {
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
