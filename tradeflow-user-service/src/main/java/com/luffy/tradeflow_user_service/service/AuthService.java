package com.luffy.tradeflow_user_service.service;

import com.luffy.tradeflow.common.security.JwtProperties;
import com.luffy.tradeflow.common.web.ApiException;
import com.luffy.tradeflow_user_service.dto.AuthDtos.LoginRequest;
import com.luffy.tradeflow_user_service.dto.AuthDtos.TokenResponse;
import com.luffy.tradeflow_user_service.dto.AuthDtos.UserResponse;
import com.luffy.tradeflow_user_service.entity.User;
import com.luffy.tradeflow_user_service.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

@Service
public class AuthService {

    /** Same message for unknown email and wrong password, so accounts cannot be enumerated. */
    private static final String BAD_CREDENTIALS = "Invalid email or password";

    private final UserRepository userRepository;
    private final BCryptPasswordEncoder passwordEncoder;
    private final JwtEncoder jwtEncoder;
    private final JwtProperties jwtProperties;
    private final LoginAttemptService loginAttempts;
    private final Clock clock;

    public AuthService(UserRepository userRepository, BCryptPasswordEncoder passwordEncoder, JwtEncoder jwtEncoder,
                       JwtProperties jwtProperties, LoginAttemptService loginAttempts, Clock clock) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtEncoder = jwtEncoder;
        this.jwtProperties = jwtProperties;
        this.loginAttempts = loginAttempts;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public TokenResponse login(LoginRequest request, String clientAddress) {
        String email = UserService.normalizeEmail(request.email());
        loginAttempts.checkAllowed(email, clientAddress);
        User user = userRepository.findByMailId(email)
                .filter(u -> passwordEncoder.matches(request.password(), u.getPassword()))
                .orElse(null);
        if (user == null) {
            loginAttempts.recordFailure(email, clientAddress);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "BAD_CREDENTIALS", BAD_CREDENTIALS);
        }
        loginAttempts.recordSuccess(email);
        if (!user.isActive()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_DISABLED", "This account is disabled");
        }
        return issueToken(user);
    }

    public TokenResponse issueToken(User user) {
        Instant now = clock.instant();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(jwtProperties.issuer())
                .subject(user.getId().toString())
                .issuedAt(now)
                .expiresAt(now.plus(jwtProperties.accessTokenTtl()))
                .claim("email", user.getMailId())
                .claim("name", user.getName())
                .claim("roles", List.of(user.getRole()))
                .build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
        return new TokenResponse(token, "Bearer", jwtProperties.accessTokenTtl().toSeconds(), UserResponse.from(user));
    }
}
