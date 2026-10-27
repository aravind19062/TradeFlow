package com.luffy.tradeflow_user_service.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "users")
@Data
public class User {
    public static final String ROLE_USER = "USER";
    public static final String ROLE_ADMIN = "ADMIN";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;
    String name;
    LocalDate dateOfBirth;
    @Column(unique = true, nullable = false)
    String mailId;
    String gender;
    String panNumber;
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


}
