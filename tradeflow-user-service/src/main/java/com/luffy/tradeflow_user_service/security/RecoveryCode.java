package com.luffy.tradeflow_user_service.security;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** One-time recovery code; only a BCrypt hash is stored. */
@Entity
@Table(name = "recovery_codes")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RecoveryCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "user_id", nullable = false)
    private long userId;
    @Column(name = "code_hash", nullable = false)
    private String codeHash;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "used_at")
    private Instant usedAt;

    RecoveryCode(long userId, String codeHash, Instant now) {
        this.userId = userId;
        this.codeHash = codeHash;
        this.createdAt = now;
    }

    void use(Instant at) {
        this.usedAt = at;
    }
}
