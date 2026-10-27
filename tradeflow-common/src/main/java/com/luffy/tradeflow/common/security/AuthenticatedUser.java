package com.luffy.tradeflow.common.security;

import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.Set;

/**
 * The caller, as asserted by a validated JWT. Controllers take this as a method
 * parameter; user ids are never read from request bodies or paths, so one user
 * cannot address another user's data.
 */
public record AuthenticatedUser(long id, String email, Set<String> roles, String token) {

    public static final String ROLE_ADMIN = "ADMIN";

    public static AuthenticatedUser from(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        return new AuthenticatedUser(Long.parseLong(jwt.getSubject()), jwt.getClaimAsString("email"),
                roles == null ? Set.of() : Set.copyOf(roles), jwt.getTokenValue());
    }

    public boolean isAdmin() {
        return roles.contains(ROLE_ADMIN);
    }
}
