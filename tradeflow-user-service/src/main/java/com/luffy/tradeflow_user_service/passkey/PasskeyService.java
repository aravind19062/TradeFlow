package com.luffy.tradeflow_user_service.passkey;

import com.luffy.tradeflow.common.web.ApiException;
import com.luffy.tradeflow_user_service.entity.User;
import com.luffy.tradeflow_user_service.repository.UserRepository;
import com.luffy.tradeflow_user_service.security.ClientInfo;
import com.luffy.tradeflow_user_service.security.SecurityEvent;
import com.luffy.tradeflow_user_service.security.SecurityEventService;
import com.luffy.tradeflow_user_service.security.SessionService;
import com.luffy.tradeflow_user_service.service.AuthService;
import com.webauthn4j.WebAuthnManager;
import com.webauthn4j.converter.AttestedCredentialDataConverter;
import com.webauthn4j.converter.exception.DataConversionException;
import com.webauthn4j.converter.util.ObjectConverter;
import com.webauthn4j.credential.CredentialRecord;
import com.webauthn4j.credential.CredentialRecordImpl;
import com.webauthn4j.data.AuthenticationData;
import com.webauthn4j.data.AuthenticationParameters;
import com.webauthn4j.data.AuthenticationRequest;
import com.webauthn4j.data.PublicKeyCredentialParameters;
import com.webauthn4j.data.PublicKeyCredentialType;
import com.webauthn4j.data.RegistrationData;
import com.webauthn4j.data.RegistrationParameters;
import com.webauthn4j.data.RegistrationRequest;
import com.webauthn4j.data.attestation.authenticator.AttestedCredentialData;
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier;
import com.webauthn4j.data.attestation.statement.NoneAttestationStatement;
import com.webauthn4j.data.client.Origin;
import com.webauthn4j.data.client.challenge.DefaultChallenge;
import com.webauthn4j.server.ServerProperty;
import com.webauthn4j.verifier.exception.VerificationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Passkey registration and sign-in. Credentials are discoverable and user verification (fingerprint,
 * face or device PIN) is required, so a passkey on its own is a two-factor sign-in. Live-trading
 * step-up and bank changes still need the password and TOTP.
 */
@Service
public class PasskeyService {

    public record PasskeyView(UUID id, String name, Instant createdAt, Instant lastUsedAt) {
        static PasskeyView from(UserPasskey p) {
            return new PasskeyView(p.getId(), p.getName(), p.getCreatedAt(), p.getLastUsedAt());
        }
    }

    public record Options(UUID challengeId, Map<String, Object> publicKey) {
    }

    private static final Duration CHALLENGE_TTL = Duration.ofMinutes(3);
    private static final int MAX_PASSKEYS = 10;
    private static final long MAX_OPEN_CHALLENGES = 2_000;
    private static final List<PublicKeyCredentialParameters> ALGORITHMS = List.of(
            new PublicKeyCredentialParameters(PublicKeyCredentialType.PUBLIC_KEY, COSEAlgorithmIdentifier.ES256),
            new PublicKeyCredentialParameters(PublicKeyCredentialType.PUBLIC_KEY, COSEAlgorithmIdentifier.RS256));

    private final UserPasskeyRepository passkeys;
    private final PasskeyChallengeRepository challenges;
    private final UserRepository users;
    private final AuthService auth;
    private final SessionService sessions;
    private final SecurityEventService events;
    private final Clock clock;
    private final String rpId;
    private final Set<Origin> origins;
    private final WebAuthnManager webAuthn;
    private final AttestedCredentialDataConverter attestedConverter;
    private final SecureRandom random = new SecureRandom();

    public PasskeyService(UserPasskeyRepository passkeys, PasskeyChallengeRepository challenges, UserRepository users, AuthService auth,
                          SessionService sessions, SecurityEventService events, Clock clock,
                          @Value("${tradeflow.passkeys.rp-id:localhost}") String rpId,
                          @Value("${tradeflow.passkeys.origins:http://localhost:3000,http://localhost:5173}") String origins) {
        this.passkeys = passkeys;
        this.challenges = challenges;
        this.users = users;
        this.auth = auth;
        this.sessions = sessions;
        this.events = events;
        this.clock = clock;
        this.rpId = rpId;
        this.origins = Arrays.stream(origins.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .map(Origin::new).collect(Collectors.toSet());
        ObjectConverter converter = new ObjectConverter();
        this.webAuthn = WebAuthnManager.createNonStrictWebAuthnManager(converter);
        this.attestedConverter = new AttestedCredentialDataConverter(converter);
    }

    @Transactional(readOnly = true)
    public List<PasskeyView> list(long userId) {
        return passkeys.findByUserIdOrderByCreatedAtDesc(userId).stream().map(PasskeyView::from).toList();
    }

    @Transactional(noRollbackFor = ApiException.class)
    public Options registrationOptions(long userId, String password, String code, ClientInfo client) {
        User user = user(userId);
        auth.reauthenticate(user, password, code, client, SecurityEvent.Type.PASSKEY_CHANGE_FAILED);
        if (passkeys.countByUserId(userId) >= MAX_PASSKEYS) {
            throw ApiException.conflict("TOO_MANY_PASSKEYS", "Remove a passkey before adding another");
        }
        PasskeyChallenge challenge = newChallenge(userId, PasskeyChallenge.REGISTER);
        return new Options(challenge.getId(), Map.of(
                "challenge", challenge.getChallenge(),
                "rp", Map.of("id", rpId, "name", "TradeFlow"),
                "user", Map.of("id", encode(userHandle(userId)), "name", user.getMailId(), "displayName", user.getName()),
                "pubKeyCredParams", List.of(Map.of("type", "public-key", "alg", -7), Map.of("type", "public-key", "alg", -257)),
                "authenticatorSelection", Map.of("residentKey", "required", "requireResidentKey", true, "userVerification", "required"),
                "attestation", "none",
                "timeout", CHALLENGE_TTL.toMillis(),
                "excludeCredentials", passkeys.findByUserIdOrderByCreatedAtDesc(userId).stream()
                        .map(p -> Map.of("type", "public-key", "id", p.getCredentialId())).toList()));
    }

    @Transactional(noRollbackFor = ApiException.class)
    public PasskeyView register(long userId, UUID challengeId, String name, String clientDataJson, String attestationObject,
                                ClientInfo client) {
        PasskeyChallenge challenge = redeem(challengeId, PasskeyChallenge.REGISTER);
        if (challenge.getUserId() == null || challenge.getUserId() != userId) {
            throw invalid();
        }
        RegistrationData data;
        try {
            data = webAuthn.verify(new RegistrationRequest(decode(attestationObject), decode(clientDataJson)),
                    new RegistrationParameters(server(challenge), ALGORITHMS, true, true));
        } catch (VerificationException | DataConversionException | IllegalArgumentException e) {
            throw invalid();
        }
        var authenticator = data.getAttestationObject().getAuthenticatorData();
        AttestedCredentialData attested = authenticator.getAttestedCredentialData();
        String credentialId = encode(attested.getCredentialId());
        if (passkeys.findByCredentialId(credentialId).isPresent()) {
            throw ApiException.conflict("PASSKEY_EXISTS", "This passkey is already registered");
        }
        UserPasskey saved = passkeys.save(new UserPasskey(userId, credentialId, encode(attestedConverter.convert(attested)),
                authenticator.getSignCount(), authenticator.isFlagBE(), authenticator.isFlagBS(), name.trim(), clock.instant()));
        events.record(userId, SecurityEvent.Type.PASSKEY_ADDED, null, saved.getName(), client);
        return PasskeyView.from(saved);
    }

    @Transactional(noRollbackFor = ApiException.class)
    public void remove(long userId, UUID id, String password, String code, ClientInfo client) {
        UserPasskey passkey = passkeys.findByIdAndUserId(id, userId).orElseThrow(() -> ApiException.notFound("Passkey"));
        auth.reauthenticate(user(userId), password, code, client, SecurityEvent.Type.PASSKEY_CHANGE_FAILED);
        passkeys.delete(passkey);
        events.record(userId, SecurityEvent.Type.PASSKEY_REMOVED, null, passkey.getName(), client);
    }

    @Transactional
    public Options loginOptions() {
        Instant now = clock.instant();
        challenges.deleteExpired(now);
        // The endpoint is public, so cap unused challenges instead of letting them fill the table.
        if (challenges.countByExpiresAtAfter(now) >= MAX_OPEN_CHALLENGES) {
            throw ApiException.tooManyRequests("TOO_MANY_REQUESTS", "Too many sign-in attempts; try again shortly", 60);
        }
        PasskeyChallenge challenge = newChallenge(null, PasskeyChallenge.LOGIN);
        return new Options(challenge.getId(), Map.of(
                "challenge", challenge.getChallenge(), "rpId", rpId, "userVerification", "required",
                "timeout", CHALLENGE_TTL.toMillis(), "allowCredentials", List.of()));
    }

    // noRollbackFor: a failed attempt on a known passkey must stay in the audit log.
    @Transactional(noRollbackFor = ApiException.class)
    public SessionService.Session login(UUID challengeId, String credentialId, String clientDataJson, String authenticatorData,
                                        String signature, String userHandle, ClientInfo client) {
        PasskeyChallenge challenge = redeem(challengeId, PasskeyChallenge.LOGIN);
        UserPasskey passkey = passkeys.findByCredentialId(credentialId).orElseThrow(PasskeyService::loginFailed);
        User user = user(passkey.getUserId());
        AuthenticationData data;
        try {
            byte[] id = decode(credentialId);
            data = webAuthn.verify(
                    new AuthenticationRequest(id, decode(userHandle), decode(authenticatorData), decode(clientDataJson), decode(signature)),
                    new AuthenticationParameters(server(challenge), record(passkey), List.of(id), true, true));
            if (!Arrays.equals(data.getUserHandle(), userHandle(user.getId()))) {
                throw new IllegalArgumentException("user handle mismatch");
            }
        } catch (VerificationException | DataConversionException | IllegalArgumentException e) {
            events.record(user.getId(), SecurityEvent.Type.LOGIN_FAILED, null, "passkey", client);
            throw loginFailed();
        }
        if (!user.isActive()) {
            throw loginFailed();
        }
        var authenticator = data.getAuthenticatorData();
        passkey.markUsed(authenticator.getSignCount(), authenticator.isFlagBS(), clock.instant());
        events.record(user.getId(), SecurityEvent.Type.LOGIN_SUCCEEDED, null, "passkey", client);
        return sessions.start(user, List.of("passkey"), client);
    }

    private CredentialRecord record(UserPasskey p) {
        return new CredentialRecordImpl(new NoneAttestationStatement(), true, p.isBackupEligible(), p.isBackupState(),
                p.getSignCount(), attestedConverter.convert(decode(p.getAttestedData())), null, null, null, null);
    }

    private ServerProperty server(PasskeyChallenge challenge) {
        return new ServerProperty(origins, rpId, new DefaultChallenge(decode(challenge.getChallenge())));
    }

    private PasskeyChallenge newChallenge(Long userId, String purpose) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return challenges.save(new PasskeyChallenge(userId, purpose, encode(bytes), clock.instant().plus(CHALLENGE_TTL)));
    }

    private PasskeyChallenge redeem(UUID id, String purpose) {
        if (challenges.redeem(id, purpose, clock.instant()) != 1) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PASSKEY_CHALLENGE_INVALID", "This request has expired; please try again");
        }
        return challenges.findById(id).orElseThrow(PasskeyService::invalid);
    }

    private User user(long id) {
        return users.findById(id).orElseThrow(() -> ApiException.notFound("User"));
    }

    private static byte[] userHandle(long userId) {
        return ("tf:" + userId).getBytes(StandardCharsets.UTF_8);
    }

    private static ApiException invalid() {
        return new ApiException(HttpStatus.BAD_REQUEST, "PASSKEY_VERIFICATION_FAILED", "The passkey could not be verified");
    }

    private static ApiException loginFailed() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "PASSKEY_LOGIN_FAILED", "That passkey could not sign you in");
    }

    private static String encode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static byte[] decode(String value) {
        return Base64.getUrlDecoder().decode(value);
    }
}
