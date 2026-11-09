package com.luffy.tradeflow_user_service.profile;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;

public final class ProfileDtos {
    private ProfileDtos() {
    }

    /** Editable details. Email, role and PAN are deliberately absent. */
    public record ProfileUpdateRequest(
            @NotBlank @Size(max = 100) String name,
            @Past LocalDate dateOfBirth,
            @Size(max = 20) String gender,
            @Pattern(regexp = "^\\+?[0-9]{10,15}$", message = "must be 10-15 digits") String mobileNumber,
            @Size(max = 100) String occupation,
            @Size(max = 20) String maritalStatus,
            @Size(max = 50) String incomeRange) {
    }

    public record BankAccountRequest(
            @NotBlank @Size(max = 100) String accountHolderName,
            @NotBlank @Size(max = 100) String bankName,
            @NotBlank @Pattern(regexp = "^[0-9]{9,18}$", message = "must be 9-18 digits") String accountNumber,
            @NotBlank @Pattern(regexp = "^[A-Za-z]{4}0[A-Za-z0-9]{6}$", message = "must be a valid 11-character IFSC")
            String ifsc,
            @NotBlank @Pattern(regexp = "^(SAVINGS|CURRENT)$", message = "must be SAVINGS or CURRENT") String accountType,
            @NotBlank String password,
            @NotBlank @Size(min = 6, max = 6) String code) {

        @Override
        public String toString() {
            return "BankAccountRequest[redacted]";
        }
    }

    public record ReauthenticationRequest(@NotBlank String password, @NotBlank @Size(min = 6, max = 6) String code) {
        @Override
        public String toString() {
            return "ReauthenticationRequest[redacted]";
        }
    }

    /** What the API shows: never the full account number. */
    public record BankAccountView(String accountHolderName, String bankName, String maskedAccountNumber, String ifsc,
                                  String accountType, Instant updatedAt) {
        static BankAccountView from(BankAccount a) {
            return new BankAccountView(a.getAccountHolderName(), a.getBankName(), "••••••" + a.getAccountLast4(),
                    a.getIfsc(), a.getAccountType(), a.getUpdatedAt());
        }
    }

    public record BankAccountResponse(BankAccountView account) {
    }
}
