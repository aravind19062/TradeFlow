package com.luffy.tradeflow_user_service.service;

import com.luffy.tradeflow.common.events.Events;
import com.luffy.tradeflow.common.events.Topics;
import com.luffy.tradeflow.common.outbox.OutboxWriter;
import com.luffy.tradeflow.common.web.ApiException;
import com.luffy.tradeflow_user_service.dto.RegisterRequest;
import com.luffy.tradeflow_user_service.entity.User;
import com.luffy.tradeflow_user_service.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

@Service
public class UserService {
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private BCryptPasswordEncoder passwordEncoder;
    @Autowired
    private OutboxWriter outboxWriter;

    /**
     * Saves the user and records a users.registered event in the same transaction,
     * so the portfolio-service opens a cash account if and only if the user exists.
     */
    @Transactional
    public User registerUser(User user){
        user.setMailId(normalizeEmail(user.getMailId()));
        if(userRepository.findByMailId(user.getMailId()).isPresent()){
            throw ApiException.conflict("EMAIL_ALREADY_REGISTERED", "A user with this email already exists");
        }
        user.setPassword(passwordEncoder.encode(user.getPassword()));
        user.setActive(true);
        if (user.getRole() == null) {
            user.setRole(User.ROLE_USER);
        }
        User saved = userRepository.save(user);
        outboxWriter.append(Topics.USERS_REGISTERED, saved.getId().toString(),
                new Events.UserRegistered(saved.getId(), saved.getMailId(), saved.getName()));
        return saved;
    }

    @Transactional
    public User register(RegisterRequest request) {
        User user = new User();
        user.setName(request.name().trim());
        user.setMailId(request.email());
        user.setPassword(request.password());
        user.setDateOfBirth(request.dateOfBirth());
        user.setGender(request.gender());
        user.setPanNumber(request.panNumber() == null ? null : request.panNumber().toUpperCase(Locale.ROOT));
        user.setMobileNumber(request.mobileNumber());
        user.setOccupation(request.occupation());
        user.setMaritalStatus(request.maritalStatus());
        user.setIncomeRange(request.incomeRange());
        return registerUser(user);
    }

    @Transactional(readOnly = true)
    public User getById(long id) {
        return userRepository.findById(id).orElseThrow(() -> ApiException.notFound("User " + id));
    }

    public static String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}
