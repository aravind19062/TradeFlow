package com.luffy.tradeflow_user_service.security;

import com.luffy.tradeflow.common.web.ApiException;
import com.luffy.tradeflow_user_service.entity.User;
import com.luffy.tradeflow_user_service.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Sessions = refresh-token families. Access tokens are short-lived (15 min); the browser keeps
 * a session going with a rotating, HttpOnly refresh cookie. Reusing an already-rotated refresh
 * token revokes the whole session (token theft detection). Bumping the user's token version
 * ends every session.
 */
@Service
public class SessionService {

    public record Session(String refreshToken, Instant refreshExpiresAt, TokenIssuer.Issued access, User user,
                          UUID sessionId) {
    }

    private static final String UNAUTHENTICATED = "Your session has ended; please sign in again";
    /** A just-rotated token presented again within this window is a concurrent refresh, not theft. */
    static final java.time.Duration RACE_GRACE = java.time.Duration.ofSeconds(10);

    private final RefreshTokenRepository tokens;
    private final UserRepository users;
    private final TokenIssuer issuer;
    private final AuthProperties properties;
    private final SecurityEventService events;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public SessionService(RefreshTokenRepository tokens, UserRepository users, TokenIssuer issuer,
                          AuthProperties properties, SecurityEventService events, Clock clock) {
        this.tokens = tokens;
        this.users = users;
        this.issuer = issuer;
        this.properties = properties;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public Session start(User user, List<String> amr, ClientInfo client) {
        Instant now = clock.instant();
        UUID family = UUID.randomUUID();
        Instant sessionEnd = now.plus(properties.sessionMaxTtl());
        String plain = newToken();
        RefreshToken row = tokens.save(new RefreshToken(user.getId(), family, hash(plain), user.getTokenVersion(),
                String.join(",", amr), now, now.plus(properties.refreshIdleTtl()), sessionEnd, client.ip(),
                client.userAgent()));
        return new Session(plain, row.getExpiresAt(), issuer.access(user, family, amr, now), user, family);
    }

    @Transactional(noRollbackFor = ApiException.class)
    public Session refresh(String presented, ClientInfo client) {
        Instant now = clock.instant();
        if (presented == null || presented.isBlank()) {
            throw unauthenticated();
        }
        RefreshToken current = tokens.findByTokenHash(hash(presented)).orElseThrow(SessionService::unauthenticated);
        if (current.getUsedAt() != null && current.getRevokedAt() == null
                && now.isBefore(current.getUsedAt().plus(RACE_GRACE))) {
            List<RefreshToken> successors = tokens.findByFamilyIdAndIssuedAtGreaterThanEqual(current.getFamilyId(),
                    current.getUsedAt()).stream().filter(t -> !t.getId().equals(current.getId())).toList();
            if (successors.stream().anyMatch(t -> t.getUsedAt() != null)) {
                // Another tab already continued the session with the new cookie: nothing to do here.
                throw new ApiException(HttpStatus.UNAUTHORIZED, "REFRESH_RACE", "Session refreshed concurrently; retry");
            }
            // The previous response never reached the client (aborted page load, dropped connection):
            // its unused successor is orphaned. Revoke it and rotate again from this token.
            successors.forEach(t -> t.revoke(now, "SUPERSEDED_LOST_RESPONSE"));
            return rotate(current, client, now);
        }
        if (current.getUsedAt() != null && current.getRevokedAt() == null) {
            // A rotated token came back later: someone else has (had) it. End the session for everyone.
            tokens.revokeFamily(current.getFamilyId(), now, "REFRESH_REUSE_DETECTED");
            events.record(current.getUserId(), SecurityEvent.Type.REFRESH_REUSE_DETECTED, null,
                    "session " + current.getFamilyId(), client);
            throw unauthenticated();
        }
        if (current.getRevokedAt() != null || !now.isBefore(current.getExpiresAt())) {
            throw unauthenticated();
        }
        return rotate(current, client, now);
    }

    private Session rotate(RefreshToken current, ClientInfo client, Instant now) {
        User user = users.findById(current.getUserId()).orElseThrow(SessionService::unauthenticated);
        if (!user.isActive() || user.getTokenVersion() != current.getTokenVersion()) {
            tokens.revokeFamily(current.getFamilyId(), now, "TOKEN_VERSION_CHANGED");
            throw unauthenticated();
        }
        if (!now.isBefore(current.getSessionExpiresAt())) {
            throw unauthenticated();
        }
        current.markUsed(now);
        String plain = newToken();
        RefreshToken next = tokens.save(new RefreshToken(user.getId(), current.getFamilyId(), hash(plain),
                current.getTokenVersion(), current.getAmr(), now, now.plus(properties.refreshIdleTtl()),
                current.getSessionExpiresAt(), client.ip(), client.userAgent()));
        Instant authTime = current.getSessionExpiresAt().minus(properties.sessionMaxTtl());
        List<String> amr = Arrays.asList(current.getAmr().split(","));
        return new Session(plain, next.getExpiresAt(), issuer.access(user, current.getFamilyId(), amr, authTime),
                user, current.getFamilyId());
    }

    /** Ends the session the cookie belongs to. Unknown or expired tokens are ignored (idempotent). */
    @Transactional
    public void logout(String presented, ClientInfo client) {
        if (presented == null || presented.isBlank()) {
            return;
        }
        tokens.findByTokenHash(hash(presented)).ifPresent(t -> {
            tokens.revokeFamily(t.getFamilyId(), clock.instant(), "SIGNED_OUT");
            events.record(t.getUserId(), SecurityEvent.Type.SIGNED_OUT, null, null, client);
        });
    }

    /** A signed-in device. */
    public record ActiveSession(UUID id, String clientIp, String userAgent, Instant startedAt, Instant lastActiveAt,
                                boolean current) {
    }

    @Transactional(readOnly = true)
    public List<ActiveSession> active(long userId, int tokenVersion, UUID currentSession) {
        List<RefreshToken> live = tokens.findLive(userId, tokenVersion, clock.instant());
        if (live.isEmpty()) {
            return List.of();
        }
        var starts = new java.util.HashMap<UUID, Instant>();
        for (Object[] row : tokens.sessionStarts(live.stream().map(RefreshToken::getFamilyId).toList())) {
            starts.put((UUID) row[0], (Instant) row[1]);
        }
        return live.stream()
                .map(t -> new ActiveSession(t.getFamilyId(), t.getClientIp(), t.getUserAgent(),
                        starts.get(t.getFamilyId()), t.getIssuedAt(), t.getFamilyId().equals(currentSession)))
                .sorted(java.util.Comparator.comparing(ActiveSession::current).reversed()
                        .thenComparing(ActiveSession::lastActiveAt, java.util.Comparator.reverseOrder()))
                .toList();
    }

    /** Ends one other device's session. Its access token stops working when it expires (15 minutes at most). */
    @Transactional
    public void revoke(long userId, UUID session, UUID currentSession, ClientInfo client) {
        if (!tokens.existsByFamilyIdAndUserId(session, userId)) {
            throw ApiException.notFound("Session");
        }
        if (session.equals(currentSession)) {
            throw ApiException.conflict("CURRENT_SESSION", "Use Sign out to end the session you are using");
        }
        tokens.revokeFamily(session, clock.instant(), "REVOKED_BY_USER");
        events.record(userId, SecurityEvent.Type.SESSION_REVOKED, null, null, client);
    }

    /** Ends every session and invalidates outstanding access tokens (via the token version). */
    @Transactional
    public void endAllSessions(long userId, String reason) {
        User user = users.findById(userId).orElseThrow(() -> ApiException.notFound("User " + userId));
        user.setTokenVersion(user.getTokenVersion() + 1);
        tokens.revokeAllForUser(userId, clock.instant(), reason);
    }

    /** Whether an access token's session is still valid (token version unchanged, user active). */
    @Transactional(readOnly = true)
    public boolean isCurrent(long userId, int tokenVersion) {
        return users.findById(userId).map(u -> u.isActive() && u.getTokenVersion() == tokenVersion).orElse(false);
    }

    private String newToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ApiException unauthenticated() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "SESSION_EXPIRED", UNAUTHENTICATED);
    }
}
