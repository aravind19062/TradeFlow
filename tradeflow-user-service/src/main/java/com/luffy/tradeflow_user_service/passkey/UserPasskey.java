package com.luffy.tradeflow_user_service.passkey;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/** A registered passkey: its public key and signature counter. */
@Entity
@Table(name = "user_passkeys")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserPasskey {

    @Id
    private UUID id;
    @Column(name = "user_id", nullable = false)
    private long userId;
    @Column(name = "credential_id", nullable = false, unique = true)
    private String credentialId;
    @Column(name = "attested_data", nullable = false)
    private String attestedData;
    @Column(name = "sign_count", nullable = false)
    private long signCount;
    @Column(name = "backup_eligible", nullable = false)
    private boolean backupEligible;
    @Column(name = "backup_state", nullable = false)
    private boolean backupState;
    @Column(nullable = false)
    private String name;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "last_used_at")
    private Instant lastUsedAt;
    @Version
    private long version;

    UserPasskey(long userId, String credentialId, String attestedData, long signCount, boolean backupEligible,
                boolean backupState, String name, Instant now) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.credentialId = credentialId;
        this.attestedData = attestedData;
        this.signCount = signCount;
        this.backupEligible = backupEligible;
        this.backupState = backupState;
        this.name = name;
        this.createdAt = now;
    }

    void markUsed(long newSignCount, boolean newBackupState, Instant now) {
        this.signCount = newSignCount;
        this.backupState = newBackupState;
        this.lastUsedAt = now;
    }
}
