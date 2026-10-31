package com.luffy.tradeflow_user_service.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "users")
@Getter
@Setter
@ToString(exclude = "password")
public class User {
    public static final String ROLE_USER = "USER";
    public static final String ROLE_ADMIN = "ADMIN";
    /** Authorised to review readiness cases (e.g. after repeated failed assessments). */
    public static final String ROLE_READINESS_REVIEWER = "READINESS_REVIEWER";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;
    String name;
    LocalDate dateOfBirth;
    @Column(unique = true, nullable = false)
    String mailId;
    String gender;
    // users.pan_number is intentionally NOT mapped: TradeFlow no longer collects PAN (KYC is the
    // broker's job). See docs/PAN_MIGRATION.md for the approved clean-up of existing values.
    String mobileNumber;
    String password;
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
    boolean isActive;
    String occupation;
    String maritalStatus;
    String incomeRange;
    @Column(nullable = false)
    String role = ROLE_USER;

    /** Incremented to revoke every session and access token of this user. */
    @Column(name = "token_version", nullable = false)
    int tokenVersion;

    /** When an administrator last reset this user's MFA (live step-up is held for 24 h after). */
    @Column(name = "mfa_reset_at")
    java.time.Instant mfaResetAt;


}
