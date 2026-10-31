package com.luffy.tradeflow_live_trading_service.connection;

import com.luffy.tradeflow.common.crypto.DataEncryptor;
import com.luffy.tradeflow.common.web.ApiException;
import com.luffy.tradeflow_live_trading_service.broker.BrokerAdapter;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/**
 * The only component that sees broker tokens in plaintext, and only transiently. Tokens are
 * encrypted with a context of the connection id (a ciphertext copied to another connection fails
 * to decrypt), never logged, never serialised into a response.
 */
@Component
public class CredentialVault {

    private final BrokerCredentialRepository credentials;
    private final DataEncryptor encryptor;
    private final Clock clock;

    public CredentialVault(BrokerCredentialRepository credentials, DataEncryptor encryptor, Clock clock) {
        this.credentials = credentials;
        this.encryptor = encryptor;
        this.clock = clock;
    }

    @Transactional
    public void store(UUID connectionId, BrokerAdapter.BrokerSession session) {
        String ctx = context(connectionId);
        credentials.save(new BrokerCredential(connectionId, encryptor.encrypt(session.accessToken(), ctx + ":access"),
                session.refreshToken() == null ? null : encryptor.encrypt(session.refreshToken(), ctx + ":refresh"),
                encryptor.activeKeyId(), session.expiresAt(), clock.instant()));
    }

    @Transactional(readOnly = true)
    public String accessToken(UUID connectionId) {
        BrokerCredential c = credentials.findById(connectionId).orElseThrow(() ->
                new ApiException(HttpStatus.CONFLICT, "NO_BROKER_CONNECTION", "Connect your broker account first"));
        return encryptor.decrypt(c.getAccessTokenCiphertext(), context(connectionId) + ":access");
    }

    @Transactional
    public void erase(UUID connectionId) {
        credentials.deleteById(connectionId);
    }

    private static String context(UUID connectionId) {
        return "broker:" + connectionId;
    }
}
