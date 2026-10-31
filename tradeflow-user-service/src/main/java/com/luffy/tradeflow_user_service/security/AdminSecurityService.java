package com.luffy.tradeflow_user_service.security;

import com.luffy.tradeflow.common.security.AuthenticatedUser;
import com.luffy.tradeflow.common.web.ApiException;
import com.luffy.tradeflow_user_service.entity.User;
import com.luffy.tradeflow_user_service.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * Account recovery for a user who lost both their authenticator and their recovery codes.
 * This is NOT a sign-in bypass: it removes the second factor, ends every session and holds
 * live trading for 24 h; the user must still sign in with their password and enrol again.
 * It requires an administrator who has MFA themselves and proves it with a current code, a
 * documented reason and an identity-verification reference, and it is recorded on both accounts.
 */
@Service
public class AdminSecurityService {

    private final UserRepository users;
    private final MfaService mfa;
    private final SessionService sessions;
    private final SecurityEventService events;
    private final Clock clock;

    public AdminSecurityService(UserRepository users, MfaService mfa, SessionService sessions,
                                SecurityEventService events, Clock clock) {
        this.users = users;
        this.mfa = mfa;
        this.sessions = sessions;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public void resetMfa(AuthenticatedUser admin, long userId, String reason, String verificationReference,
                         String adminCode, ClientInfo client) {
        if (admin.id() == userId) {
            throw new ApiException(HttpStatus.FORBIDDEN, "SELF_RESET_FORBIDDEN",
                    "An administrator cannot reset their own two-factor authentication");
        }
        // The administrator proves possession of their own second factor now.
        mfa.verify(admin.id(), adminCode, null, false);
        User user = users.findById(userId).orElseThrow(() -> ApiException.notFound("User " + userId));
        if (!mfa.status(userId).enabled() && !mfa.status(userId).enrollmentPending()) {
            throw ApiException.conflict("MFA_NOT_ENABLED", "This user has no two-factor authentication to reset");
        }
        mfa.remove(userId);
        user.setMfaResetAt(clock.instant());
        sessions.endAllSessions(userId, "MFA_RESET_BY_ADMIN");
        String detail = "reason: " + reason + "; verification: " + verificationReference;
        events.record(userId, SecurityEvent.Type.MFA_RESET_BY_ADMIN, admin.id(), detail, client);
        events.record(admin.id(), SecurityEvent.Type.MFA_RESET_BY_ADMIN, admin.id(), "reset user " + userId + "; "
                + detail, client);
    }
}
