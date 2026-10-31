package com.luffy.tradeflow_live_trading_service.connection;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Broker tokens, stored only as AES-256-GCM ciphertext bound to the connection id. No getter
 * returns plaintext: decryption happens in CredentialVault at the moment of a broker call.
 */
@Entity
@Table(name = "broker_credentials")
@Getter(AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BrokerCredential {

    @Id
    @Column(name = "connection_id")
    private UUID connectionId;
    @Column(name = "access_token_ct", nullable = false)
    private String accessTokenCiphertext;
    @Column(name = "refresh_token_ct")
    private String refreshTokenCiphertext;
    @Column(name = "key_id", nullable = false)
    private String keyId;
    @Column(name = "expires_at")
    private Instant expiresAt;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    BrokerCredential(UUID connectionId, String accessCt, String refreshCt, String keyId, Instant expiresAt, Instant now) {
        this.connectionId = connectionId;
        this.accessTokenCiphertext = accessCt;
        this.refreshTokenCiphertext = refreshCt;
        this.keyId = keyId;
        this.expiresAt = expiresAt;
        this.createdAt = now;
    }

    @Override
    public String toString() {
        return "BrokerCredential[connection=" + connectionId + ", keyId=" + keyId + ", tokens=<encrypted>]";
    }
}
