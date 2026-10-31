package com.luffy.tradeflow_live_trading_service.connection;

import com.luffy.tradeflow.common.crypto.DataEncryptor;
import com.luffy.tradeflow.common.web.ApiException;
import com.luffy.tradeflow_live_trading_service.audit.LiveAuditService;
import com.luffy.tradeflow_live_trading_service.broker.BrokerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Linking a broker account through the broker's own authorisation page (redirect + one-time code,
 * with state and PKCE). TradeFlow never asks for or sees the broker password or OTP. Callers must
 * have passed {@code LiveAccessGuard} first.
 */
@Service
public class BrokerConnectionService {

    static final Duration STATE_TTL = Duration.ofMinutes(10);
    private static final Logger log = LoggerFactory.getLogger(BrokerConnectionService.class);

    public record View(UUID id, String broker, String status, String accountRef, Instant connectedAt, Instant expiresAt) {
        static View of(BrokerConnection c) {
            return new View(c.getId(), c.getBrokerCode(), c.getStatus().name(), c.getBrokerAccountRef(),
                    c.getConnectedAt(), c.getExpiresAt());
        }
    }

    public record Started(String authorizationUrl, Instant expiresAt) {
    }

    private final BrokerAdapter broker;
    private final BrokerConnectionRepository connections;
    private final OAuthStateRepository states;
    private final CredentialVault vault;
    private final DataEncryptor encryptor;
    private final LiveAuditService audit;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public BrokerConnectionService(BrokerAdapter broker, BrokerConnectionRepository connections, OAuthStateRepository states,
                                   CredentialVault vault, DataEncryptor encryptor, LiveAuditService audit, Clock clock) {
        this.broker = broker;
        this.connections = connections;
        this.states = states;
        this.vault = vault;
        this.encryptor = encryptor;
        this.audit = audit;
        this.clock = clock;
    }

    public boolean brokerConfigured() {
        return broker.enabled();
    }

    public String brokerCode() {
        return broker.code();
    }

    @Transactional(readOnly = true)
    public Optional<View> current(long userId) {
        return active(userId).map(View::of);
    }

    @Transactional(readOnly = true)
    public Optional<BrokerConnection> active(long userId) {
        return connections.findFirstByUserIdAndStatusOrderByConnectedAtDesc(userId, BrokerConnection.Status.ACTIVE)
                .filter(c -> c.usable(clock.instant()));
    }

    /** A connection that is usable now, or 409. */
    @Transactional(readOnly = true)
    public BrokerConnection requireActive(long userId) {
        return active(userId).orElseThrow(() -> ApiException.conflict("NO_BROKER_CONNECTION",
                "Connect your broker account first"));
    }

    @Transactional
    public Started start(long userId) {
        requireBroker();
        if (active(userId).isPresent()) {
            throw ApiException.conflict("ALREADY_CONNECTED", "A broker account is already connected; disconnect it first");
        }
        Instant now = clock.instant();
        BrokerConnection c = connections.save(new BrokerConnection(userId, broker.code(), now));
        String state = randomToken(32);
        String verifier = randomToken(48);
        String hash = sha256Hex(state);
        states.save(new OAuthState(hash, userId, broker.code(), c.getId(),
                encryptor.encrypt(verifier, "oauth:" + hash), now, now.plus(STATE_TTL)));
        String url = broker.authorizationUrl(state, Base64.getUrlEncoder().withoutPadding()
                .encodeToString(sha256(verifier)));
        audit.record(userId, "CONNECT_BROKER", LiveAuditService.Outcome.ALLOWED, "connection " + c.getId(), userId);
        return new Started(url, now.plus(STATE_TTL));
    }

    @Transactional(noRollbackFor = ApiException.class)
    public View complete(long userId, String state, String code) {
        requireBroker();
        if (state == null || code == null || state.isBlank() || code.isBlank()) {
            throw ApiException.badRequest("INVALID_CALLBACK", "Missing state or code");
        }
        String hash = sha256Hex(state);
        Instant now = clock.instant();
        OAuthState s = states.lock(hash).filter(x -> x.usableBy(userId, now)).orElseThrow(() ->
                ApiException.badRequest("INVALID_STATE", "This authorisation link is invalid or expired; start again"));
        s.consume(now); // one use, even if the exchange below fails
        String verifier = encryptor.decrypt(s.getVerifierCiphertext(), "oauth:" + hash);
        BrokerConnection c = connections.findById(s.getConnectionId()).orElseThrow();
        BrokerAdapter.BrokerSession session = broker.exchangeCode(code, verifier);
        vault.store(c.getId(), session);
        c.activate(session.accountRef(), now, session.expiresAt());
        audit.record(userId, "COMPLETE_CONNECTION", LiveAuditService.Outcome.ALLOWED,
                "connection " + c.getId() + " account " + session.accountRef(), userId);
        return View.of(c);
    }

    /**
     * Disconnects TradeFlow from the broker account: revokes and erases the stored tokens. Orders
     * already at the broker are NOT cancelled; the user can still manage them at the broker.
     */
    @Transactional
    public void disconnect(long userId) {
        BrokerConnection c = requireActive(userId);
        try {
            broker.revoke(vault.accessToken(c.getId()));
        } catch (RuntimeException e) {
            log.warn("Broker token revocation failed for connection {} ({}); erasing locally", c.getId(),
                    e.getClass().getSimpleName());
        }
        vault.erase(c.getId());
        c.disconnect(clock.instant());
        audit.record(userId, "DISCONNECT_BROKER", LiveAuditService.Outcome.ALLOWED, "connection " + c.getId(), userId);
    }

    @Transactional(readOnly = true)
    public List<View> history(long userId) {
        return connections.findByUserIdOrderByCreatedAtDesc(userId).stream().map(View::of).toList();
    }

    private void requireBroker() {
        if (!broker.enabled()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "BROKER_NOT_CONFIGURED",
                    "No broker is integrated yet. Live trading will open once a broker has been approved.");
        }
    }

    private String randomToken(int bytes) {
        byte[] b = new byte[bytes];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.US_ASCII));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String sha256Hex(String s) {
        return HexFormat.of().formatHex(sha256(s));
    }
}
