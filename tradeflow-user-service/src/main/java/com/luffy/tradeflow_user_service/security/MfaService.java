package com.luffy.tradeflow_user_service.security;

import com.luffy.tradeflow.common.crypto.DataEncryptor;
import com.luffy.tradeflow.common.web.ApiException;
import com.luffy.tradeflow_user_service.entity.User;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * TOTP multi-factor authentication: enrolment (password re-entry, then a code from the new
 * device), one-time recovery codes, verification with replay protection, and disabling.
 * Seeds are encrypted at rest and bound to the user id. There is no code path that skips the
 * second factor for an enrolled user: account recovery is a recovery code, or an audited
 * administrator reset that removes MFA and ends every session (see AdminSecurityService).
 */
@Service
public class MfaService {

    public static final int RECOVERY_CODE_COUNT = 10;
    private static final String ISSUER = "TradeFlow";

    public enum SecondFactor { TOTP, RECOVERY_CODE }

    public record Enrollment(String secret, String otpauthUri) {
    }

    public record Status(boolean enabled, Instant enabledAt, long recoveryCodesRemaining, boolean enrollmentPending) {
    }

    private final UserTotpRepository totps;
    private final RecoveryCodeRepository recoveryCodes;
    private final DataEncryptor encryptor;
    private final BCryptPasswordEncoder passwords;
    private final SecurityEventService events;
    private final SessionService sessions;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public MfaService(UserTotpRepository totps, RecoveryCodeRepository recoveryCodes, DataEncryptor encryptor,
                      BCryptPasswordEncoder passwords, SecurityEventService events, SessionService sessions,
                      Clock clock) {
        this.totps = totps;
        this.recoveryCodes = recoveryCodes;
        this.encryptor = encryptor;
        this.passwords = passwords;
        this.events = events;
        this.sessions = sessions;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Status status(long userId) {
        Optional<UserTotp> totp = totps.findById(userId);
        boolean enabled = totp.map(UserTotp::enabled).orElse(false);
        return new Status(enabled, totp.map(UserTotp::getEnabledAt).orElse(null),
                enabled ? recoveryCodes.countByUserIdAndUsedAtIsNull(userId) : 0,
                totp.map(t -> !t.enabled()).orElse(false));
    }

    @Transactional(readOnly = true)
    public boolean enabled(long userId) {
        return totps.findById(userId).map(UserTotp::enabled).orElse(false);
    }

    /** Step 1: requires the password; returns the new seed exactly once. */
    @Transactional
    public Enrollment beginEnrollment(User user, String password, ClientInfo client) {
        requirePassword(user, password);
        Optional<UserTotp> existing = totps.findById(user.getId());
        if (existing.map(UserTotp::enabled).orElse(false)) {
            throw ApiException.conflict("MFA_ALREADY_ENABLED", "Two-factor authentication is already enabled");
        }
        String secret = Totp.newSecret(random);
        String ciphertext = encryptor.encrypt(secret, context(user.getId()));
        Instant now = clock.instant();
        existing.ifPresentOrElse(t -> t.restart(ciphertext, now),
                () -> totps.save(new UserTotp(user.getId(), ciphertext, now)));
        events.record(user.getId(), SecurityEvent.Type.MFA_ENROLLMENT_STARTED, null, null, client);
        return new Enrollment(secret, Totp.otpauthUri(ISSUER, user.getMailId(), secret));
    }

    /** Step 2: a valid code from the authenticator proves the device has the seed. */
    @Transactional
    public List<String> confirmEnrollment(User user, String code, ClientInfo client) {
        UserTotp totp = totps.findById(user.getId())
                .orElseThrow(() -> ApiException.conflict("MFA_ENROLLMENT_NOT_STARTED", "Start enrolment first"));
        if (totp.enabled()) {
            throw ApiException.conflict("MFA_ALREADY_ENABLED", "Two-factor authentication is already enabled");
        }
        if (!clock.instant().isBefore(totp.getCreatedAt().plus(java.time.Duration.ofMinutes(15)))) {
            throw ApiException.conflict("MFA_ENROLLMENT_EXPIRED", "Enrolment expired; start again");
        }
        OptionalLong step = Totp.verify(secretOf(totp), code, clock.instant());
        if (step.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_MFA_CODE",
                    "That code is not valid; check the time on your device and try again");
        }
        totp.accept(step.getAsLong());
        totp.enable(clock.instant());
        List<String> codes = issueRecoveryCodes(user.getId());
        events.record(user.getId(), SecurityEvent.Type.MFA_ENABLED, null, null, client);
        return codes;
    }

    /**
     * Verifies a second factor for an enrolled user. A TOTP code is accepted at most once (its
     * time step is remembered); a recovery code is consumed. Returns which factor was used.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public SecondFactor verify(long userId, String code, String recoveryCode, boolean allowRecoveryCode) {
        UserTotp totp = totps.findById(userId).filter(UserTotp::enabled)
                .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "MFA_NOT_ENABLED",
                        "Two-factor authentication is not enabled for this account"));
        if (code != null && !code.isBlank()) {
            OptionalLong step = Totp.verify(secretOf(totp), code.trim(), clock.instant());
            if (step.isPresent() && step.getAsLong() > totp.getLastUsedStep()) {
                totp.accept(step.getAsLong());
                return SecondFactor.TOTP;
            }
            throw invalidCode();
        }
        if (allowRecoveryCode && recoveryCode != null && !recoveryCode.isBlank()) {
            String normalized = normalizeRecoveryCode(recoveryCode);
            for (RecoveryCode rc : recoveryCodes.findByUserIdAndUsedAtIsNull(userId)) {
                if (passwords.matches(normalized, rc.getCodeHash())) {
                    rc.use(clock.instant());
                    return SecondFactor.RECOVERY_CODE;
                }
            }
        }
        throw invalidCode();
    }

    @Transactional
    public List<String> regenerateRecoveryCodes(User user, String password, String code, ClientInfo client) {
        requirePassword(user, password);
        verify(user.getId(), code, null, false);
        List<String> codes = issueRecoveryCodes(user.getId());
        events.record(user.getId(), SecurityEvent.Type.RECOVERY_CODES_REGENERATED, null, null, client);
        return codes;
    }

    /** Requires password and a second factor; ends every session afterwards. */
    @Transactional
    public void disable(User user, String password, String code, String recoveryCode, ClientInfo client) {
        requirePassword(user, password);
        verify(user.getId(), code, recoveryCode, true);
        remove(user.getId());
        sessions.endAllSessions(user.getId(), "MFA_DISABLED");
        events.record(user.getId(), SecurityEvent.Type.MFA_DISABLED, null, null, client);
    }

    /** Removes seed and recovery codes (used by disable and by the audited admin reset). */
    @Transactional
    public void remove(long userId) {
        totps.findById(userId).ifPresent(totps::delete);
        recoveryCodes.deleteAllForUser(userId);
    }

    private List<String> issueRecoveryCodes(long userId) {
        recoveryCodes.deleteAllForUser(userId);
        List<String> plain = new ArrayList<>();
        Instant now = clock.instant();
        for (int i = 0; i < RECOVERY_CODE_COUNT; i++) {
            byte[] bytes = new byte[7];
            random.nextBytes(bytes);
            String code = Totp.base32(bytes).substring(0, 10);
            plain.add(code.substring(0, 5) + "-" + code.substring(5));
            recoveryCodes.save(new RecoveryCode(userId, passwords.encode(code), now));
        }
        return plain;
    }

    private void requirePassword(User user, String password) {
        if (password == null || !passwords.matches(password, user.getPassword())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "BAD_CREDENTIALS", "Password is incorrect");
        }
    }

    private String secretOf(UserTotp totp) {
        return encryptor.decrypt(totp.getSecretCiphertext(), context(totp.getUserId()));
    }

    private static String context(long userId) {
        return "totp:user:" + userId;
    }

    static String normalizeRecoveryCode(String code) {
        return code.replace("-", "").replace(" ", "").toUpperCase(Locale.ROOT);
    }

    private static ApiException invalidCode() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_MFA_CODE", "The verification code is not valid");
    }
}
