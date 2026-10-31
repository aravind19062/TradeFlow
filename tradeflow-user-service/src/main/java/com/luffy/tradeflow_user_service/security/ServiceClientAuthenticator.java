package com.luffy.tradeflow_user_service.security;

import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/**
 * Authenticates other TradeFlow services (client id = spring.application.name, client secret
 * from configuration) before issuing them a SERVICE token. A service can therefore obtain
 * only a SERVICE token for itself, never a user token.
 */
@Component
public class ServiceClientAuthenticator {

    private static final String LOCAL_PREFIX = "local-dev-only";
    private final Map<String, String> clients;

    public ServiceClientAuthenticator(AuthProperties properties, Environment environment) {
        // A blank secret means "this client is not configured here" (e.g. no live-trading service).
        this.clients = properties.serviceClients().entrySet().stream()
                .filter(e -> e.getValue() != null && !e.getValue().isBlank())
                .collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
        boolean local = environment.acceptsProfiles(Profiles.of("local", "test"));
        clients.forEach((name, secret) -> {
            if (secret == null || secret.length() < 24) {
                throw new IllegalStateException("Refusing to start: client secret for " + name
                        + " must be at least 24 characters");
            }
            if (secret.startsWith(LOCAL_PREFIX) && !local) {
                throw new IllegalStateException("Refusing to start: the public local-development client secret for "
                        + name + " is configured outside the 'local' profile");
            }
        });
    }

    public boolean authenticate(String clientId, String secret) {
        String expected = clientId == null ? null : clients.get(clientId);
        // Compare digests in constant time; unknown clients still pay the same cost.
        byte[] a = digest(expected == null ? "\u0000unknown" : expected);
        byte[] b = digest(secret == null ? "" : secret);
        return MessageDigest.isEqual(a, b) && expected != null;
    }

    private static byte[] digest(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
