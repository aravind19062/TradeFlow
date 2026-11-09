package com.luffy.tradeflow_user_service.passkey;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "passkey_challenges")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PasskeyChallenge {

    public static final String REGISTER = "REGISTER";
    public static final String LOGIN = "LOGIN";

    @Id
    private UUID id;
    @Column(name = "user_id")
    private Long userId;
    @Column(nullable = false)
    private String purpose;
    @Column(nullable = false)
    private String challenge;
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
    @Column(name = "used_at")
    private Instant usedAt;

    PasskeyChallenge(Long userId, String purpose, String challenge, Instant expiresAt) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.purpose = purpose;
        this.challenge = challenge;
        this.expiresAt = expiresAt;
    }
}
