package com.luffy.tradeflow_user_service.service;

import com.luffy.tradeflow.common.security.AuthenticatedUser;
import com.luffy.tradeflow.common.web.ApiException;
import com.luffy.tradeflow_user_service.dto.AuthDtos.LoginRequest;
import com.luffy.tradeflow_user_service.entity.User;
import com.luffy.tradeflow_user_service.repository.UserRepository;
import com.luffy.tradeflow_user_service.security.AuthProperties;
import com.luffy.tradeflow_user_service.security.ClientInfo;
import com.luffy.tradeflow_user_service.security.MfaChallengeService;
import com.luffy.tradeflow_user_service.security.MfaService;
import com.luffy.tradeflow_user_service.security.SecurityEvent;
import com.luffy.tradeflow_user_service.security.SecurityEventService;
import com.luffy.tradeflow_user_service.security.SessionService;
import com.luffy.tradeflow_user_service.security.TokenIssuer;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * Sign-in. Password first (throttled, no account enumeration). If the account has TOTP the
 * result is an MFA challenge, and tokens are issued only after a valid second factor. Live
 * trading additionally needs a step-up (password + TOTP again, 10-minute token).
 */
@Service
public class AuthService {

    /** Same message for unknown email and wrong password, so accounts cannot be enumerated. */
    private static final String BAD_CREDENTIALS = "Invalid email or password";

    public sealed interface LoginResult permits Authenticated, MfaRequired {
    }

    public record Authenticated(SessionService.Session session) implements LoginResult {
    }

    public record MfaRequired(String mfaToken, long expiresInSeconds) implements LoginResult {
    }

    private final UserRepository userRepository;
    private final BCryptPasswordEncoder passwordEncoder;
    private final LoginAttemptService loginAttempts;
    private final SessionService sessions;
    private final MfaService mfa;
    private final MfaChallengeService challenges;
    private final TokenIssuer issuer;
    private final SecurityEventService events;
    private final AuthProperties properties;
    private final Clock clock;

    public AuthService(UserRepository userRepository, BCryptPasswordEncoder passwordEncoder,
                       LoginAttemptService loginAttempts, SessionService sessions, MfaService mfa,
                       MfaChallengeService challenges, TokenIssuer issuer, SecurityEventService events,
                       AuthProperties properties, Clock clock) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.loginAttempts = loginAttempts;
        this.sessions = sessions;
        this.mfa = mfa;
        this.challenges = challenges;
        this.issuer = issuer;
        this.events = events;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public LoginResult login(LoginRequest request, ClientInfo client) {
        String email = UserService.normalizeEmail(request.email());
        loginAttempts.checkAllowed(email, client.ip());
        User user = userRepository.findByMailId(email)
                .filter(u -> passwordEncoder.matches(request.password(), u.getPassword()))
                .orElse(null);
        if (user == null) {
            loginAttempts.recordFailure(email, client.ip());
            userRepository.findByMailId(email).ifPresent(u ->
                    events.record(u.getId(), SecurityEvent.Type.LOGIN_FAILED, null, "wrong password", client));
            throw new ApiException(HttpStatus.UNAUTHORIZED, "BAD_CREDENTIALS", BAD_CREDENTIALS);
        }
        if (!user.isActive()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_DISABLED", "This account is disabled");
        }
        if (mfa.enabled(user.getId())) {
            // The password was right, but the account is not unlocked until the second factor.
            return new MfaRequired(challenges.issue(user.getId()), properties.mfaChallengeTtl().toSeconds());
        }
        loginAttempts.recordSuccess(email);
        events.record(user.getId(), SecurityEvent.Type.LOGIN_SUCCEEDED, null, "password", client);
        return new Authenticated(sessions.start(user, List.of("pwd"), client));
    }

    /** Second step of an MFA login. Failures count towards the account's login throttle. */
    @Transactional(noRollbackFor = ApiException.class)
    public Authenticated completeMfaLogin(String mfaToken, String code, String recoveryCode, ClientInfo client) {
        long userId = challenges.peek(mfaToken);
        User user = userRepository.findById(userId).orElseThrow(MfaChallengeService::invalidChallenge);
        loginAttempts.checkAllowed(user.getMailId(), client.ip());
        MfaService.SecondFactor factor;
        try {
            factor = mfa.verify(userId, code, recoveryCode, true);
        } catch (ApiException e) {
            challenges.recordFailure(mfaToken);
            loginAttempts.recordFailure(user.getMailId(), client.ip());
            events.record(userId, SecurityEvent.Type.MFA_CHALLENGE_FAILED, null, null, client);
            throw e;
        }
        challenges.consume(mfaToken);
        loginAttempts.recordSuccess(user.getMailId());
        if (factor == MfaService.SecondFactor.RECOVERY_CODE) {
            events.record(userId, SecurityEvent.Type.RECOVERY_CODE_USED, null, null, client);
        }
        events.record(userId, SecurityEvent.Type.LOGIN_SUCCEEDED, null, "password+" + factor.name().toLowerCase(),
                client);
        return new Authenticated(sessions.start(user, List.of("pwd", factor == MfaService.SecondFactor.TOTP
                ? "otp" : "rec"), client));
    }

    public record StepUp(TokenIssuer.Issued token) {
    }

    /**
     * Re-authentication for live trading: current session, password and a TOTP code (recovery
     * codes are not accepted here). Refused for 24 h after an administrator MFA reset.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public StepUp stepUp(AuthenticatedUser caller, int tokenVersion, String sessionId, String password, String code,
                         ClientInfo client) {
        User user = userRepository.findById(caller.id()).orElseThrow(() -> ApiException.notFound("User"));
        if (!user.isActive() || user.getTokenVersion() != tokenVersion) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "SESSION_EXPIRED", "Your session has ended; sign in again");
        }
        if (!mfa.enabled(user.getId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "MFA_REQUIRED",
                    "Enable two-factor authentication before using live trading");
        }
        if (user.getMfaResetAt() != null
                && clock.instant().isBefore(user.getMfaResetAt().plus(properties.stepUpHoldAfterMfaReset()))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "STEP_UP_ON_HOLD", "Live trading is unavailable for "
                    + properties.stepUpHoldAfterMfaReset().toHours() + " hours after a two-factor reset");
        }
        loginAttempts.checkAllowed(user.getMailId(), client.ip());
        boolean ok = password != null && passwordEncoder.matches(password, user.getPassword());
        if (ok) {
            try {
                mfa.verify(user.getId(), code, null, false);
            } catch (ApiException e) {
                ok = false;
            }
        }
        if (!ok) {
            loginAttempts.recordFailure(user.getMailId(), client.ip());
            events.record(user.getId(), SecurityEvent.Type.STEP_UP_FAILED, null, null, client);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "STEP_UP_FAILED", "Password or verification code is incorrect");
        }
        events.record(user.getId(), SecurityEvent.Type.STEP_UP_SUCCEEDED, null, null, client);
        return new StepUp(issuer.stepUp(user, UUID.fromString(sessionId)));
    }
}
