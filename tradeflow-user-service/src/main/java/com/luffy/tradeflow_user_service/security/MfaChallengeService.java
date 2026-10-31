package com.luffy.tradeflow_user_service.security;

import com.luffy.tradeflow.common.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;

/** Short-lived, attempt-limited tickets linking the password step of a login to its MFA step. */
@Service
public class MfaChallengeService {

    private final MfaChallengeRepository repository;
    private final AuthProperties properties;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public MfaChallengeService(MfaChallengeRepository repository, AuthProperties properties, Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public String issue(long userId) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant now = clock.instant();
        repository.save(new MfaChallenge(SessionService.hash(token), userId, now, now.plus(properties.mfaChallengeTtl())));
        return token;
    }

    /** The user the challenge belongs to, if it is still usable. */
    @Transactional(readOnly = true, noRollbackFor = ApiException.class)
    public long peek(String token) {
        return find(token).getUserId();
    }

    /**
     * Counts a wrong code. Joins the caller's transaction, which already holds the row lock and
     * does not roll back on the resulting ApiException (a separate transaction would wait on that
     * lock forever).
     */
    @Transactional(noRollbackFor = ApiException.class)
    public void recordFailure(String token) {
        repository.findByTokenHash(SessionService.hash(token)).ifPresent(MfaChallenge::failedAttempt);
    }

    @Transactional(noRollbackFor = ApiException.class)
    public void consume(String token) {
        find(token).consume(clock.instant());
    }

    private MfaChallenge find(String token) {
        if (token == null || token.isBlank()) {
            throw invalidChallenge();
        }
        return repository.findByTokenHash(SessionService.hash(token))
                .filter(c -> c.usable(clock.instant()))
                .orElseThrow(MfaChallengeService::invalidChallenge);
    }

    public static ApiException invalidChallenge() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "MFA_CHALLENGE_EXPIRED",
                "The sign-in attempt expired or had too many wrong codes; sign in again");
    }
}
