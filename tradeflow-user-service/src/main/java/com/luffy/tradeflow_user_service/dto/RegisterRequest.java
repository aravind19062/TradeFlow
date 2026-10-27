package com.luffy.tradeflow_user_service.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record RegisterRequest(
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Email @Size(max = 255) String email,
        @NotBlank @Size(min = 8, max = 72, message = "must be 8-72 characters") String password,
        @Past LocalDate dateOfBirth,
        @Size(max = 20) String gender,
        @Pattern(regexp = "^[A-Za-z]{5}[0-9]{4}[A-Za-z]$", message = "must be a valid PAN, e.g. ABCDE1234F")
        String panNumber,
        @Pattern(regexp = "^\\+?[0-9]{10,15}$", message = "must be 10-15 digits") String mobileNumber,
        @Size(max = 100) String occupation,
        @Size(max = 20) String maritalStatus,
        @Size(max = 50) String incomeRange) {
}
