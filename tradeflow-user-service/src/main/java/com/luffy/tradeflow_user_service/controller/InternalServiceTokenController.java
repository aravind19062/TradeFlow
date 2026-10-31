package com.luffy.tradeflow_user_service.controller;

import com.luffy.tradeflow.common.web.ApiException;
import com.luffy.tradeflow_user_service.security.ServiceClientAuthenticator;
import com.luffy.tradeflow_user_service.security.TokenIssuer;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;

/**
 * Client-credentials token endpoint for other TradeFlow services (HTTP Basic: service name and
 * its client secret). Never routed by the API gateway (/internal/** is denied there).
 */
@Hidden
@RestController
public class InternalServiceTokenController {

    private static final Logger log = LoggerFactory.getLogger(InternalServiceTokenController.class);

    public record ServiceTokenResponse(String accessToken, String tokenType, long expiresInSeconds) {
    }

    private final ServiceClientAuthenticator clients;
    private final TokenIssuer issuer;
    private final Clock clock;

    public InternalServiceTokenController(ServiceClientAuthenticator clients, TokenIssuer issuer, Clock clock) {
        this.clients = clients;
        this.issuer = issuer;
        this.clock = clock;
    }

    @PostMapping("/internal/auth/service-token")
    public ServiceTokenResponse token(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        String clientId = null;
        String secret = null;
        if (header != null && header.startsWith("Basic ")) {
            try {
                String decoded = new String(Base64.getDecoder().decode(header.substring(6)), StandardCharsets.UTF_8);
                int colon = decoded.indexOf(':');
                if (colon > 0) {
                    clientId = decoded.substring(0, colon);
                    secret = decoded.substring(colon + 1);
                }
            } catch (IllegalArgumentException ignored) {
                // falls through to the 401 below
            }
        }
        if (!clients.authenticate(clientId, secret)) {
            log.warn("Refused a service token to client '{}' from {}", clientId, request.getRemoteAddr());
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CLIENT", "Unknown service client or wrong secret");
        }
        TokenIssuer.Issued token = issuer.service(clientId);
        return new ServiceTokenResponse(token.token(), "Bearer",
                Duration.between(clock.instant(), token.expiresAt()).getSeconds());
    }
}
