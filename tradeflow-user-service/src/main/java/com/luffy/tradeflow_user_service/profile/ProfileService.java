package com.luffy.tradeflow_user_service.profile;

import com.luffy.tradeflow.common.crypto.DataEncryptor;
import com.luffy.tradeflow.common.web.ApiException;
import com.luffy.tradeflow_user_service.entity.User;
import com.luffy.tradeflow_user_service.profile.ProfileDtos.BankAccountRequest;
import com.luffy.tradeflow_user_service.profile.ProfileDtos.BankAccountView;
import com.luffy.tradeflow_user_service.profile.ProfileDtos.ProfileUpdateRequest;
import com.luffy.tradeflow_user_service.repository.UserRepository;
import com.luffy.tradeflow_user_service.security.ClientInfo;
import com.luffy.tradeflow_user_service.security.SecurityEvent;
import com.luffy.tradeflow_user_service.security.SecurityEventService;
import com.luffy.tradeflow_user_service.service.AuthService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Locale;
import java.util.Optional;

/** The user's own details and bank account. Bank changes need a fresh password + authenticator code. */
@Service
public class ProfileService {

    private final UserRepository users;
    private final BankAccountRepository accounts;
    private final AuthService auth;
    private final DataEncryptor encryptor;
    private final SecurityEventService events;
    private final Clock clock;

    public ProfileService(UserRepository users, BankAccountRepository accounts, AuthService auth,
                          DataEncryptor encryptor, SecurityEventService events, Clock clock) {
        this.users = users;
        this.accounts = accounts;
        this.auth = auth;
        this.encryptor = encryptor;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public User updateProfile(long userId, ProfileUpdateRequest request, ClientInfo client) {
        User user = user(userId);
        user.setName(request.name().trim());
        user.setDateOfBirth(request.dateOfBirth());
        user.setGender(blankToNull(request.gender()));
        user.setMobileNumber(blankToNull(request.mobileNumber()));
        user.setOccupation(blankToNull(request.occupation()));
        user.setMaritalStatus(blankToNull(request.maritalStatus()));
        user.setIncomeRange(blankToNull(request.incomeRange()));
        events.record(userId, SecurityEvent.Type.PROFILE_UPDATED, null, null, client);
        return user;
    }

    @Transactional(readOnly = true)
    public Optional<BankAccountView> bankAccount(long userId) {
        return accounts.findById(userId).map(BankAccountView::from);
    }

    // noRollbackFor: a wrong code must still count towards the lockout and appear in the audit log.
    @Transactional(noRollbackFor = ApiException.class)
    public BankAccountView saveBankAccount(long userId, BankAccountRequest request, ClientInfo client) {
        auth.reauthenticate(user(userId), request.password(), request.code(), client,
                SecurityEvent.Type.BANK_DETAILS_CHANGE_FAILED);
        BankAccount account = accounts.findById(userId).orElseGet(() -> new BankAccount(userId, clock.instant()));
        String number = request.accountNumber();
        String last4 = number.substring(number.length() - 4);
        account.update(request.accountHolderName().trim(), request.bankName().trim(),
                encryptor.encrypt(number, context(userId)), last4, request.ifsc().toUpperCase(Locale.ROOT),
                request.accountType(), clock.instant());
        accounts.save(account);
        events.record(userId, SecurityEvent.Type.BANK_DETAILS_SAVED, null, "ending " + last4, client);
        return BankAccountView.from(account);
    }

    @Transactional(noRollbackFor = ApiException.class)
    public void removeBankAccount(long userId, String password, String code, ClientInfo client) {
        auth.reauthenticate(user(userId), password, code, client, SecurityEvent.Type.BANK_DETAILS_CHANGE_FAILED);
        if (accounts.existsById(userId)) {
            accounts.deleteById(userId);
            events.record(userId, SecurityEvent.Type.BANK_DETAILS_REMOVED, null, null, client);
        }
    }

    private User user(long id) {
        return users.findById(id).orElseThrow(() -> ApiException.notFound("User"));
    }

    private static String context(long userId) {
        return "bank-account:user:" + userId;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
