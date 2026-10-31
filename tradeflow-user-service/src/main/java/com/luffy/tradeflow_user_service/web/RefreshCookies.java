package com.luffy.tradeflow_user_service.web;

import com.luffy.tradeflow.common.web.ApiException;
import com.luffy.tradeflow_user_service.security.AuthProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * The refresh token travels only in an HttpOnly, SameSite=Strict cookie scoped to /api/auth.
 * Scripts cannot read it, other sites cannot send it, and it never reaches the other services.
 * The endpoints that use it also require the X-TradeFlow-Client header, which a cross-site
 * form or link cannot add (CSRF defence in depth).
 */
@Component
public class RefreshCookies {

    public static final String NAME = "tf_refresh";
    public static final String CLIENT_HEADER = "X-TradeFlow-Client";
    private static final String PATH = "/api/auth";

    private final boolean secure;
    private final Clock clock;

    public RefreshCookies(AuthProperties properties, Clock clock) {
        this.secure = properties.cookieSecure();
        this.clock = clock;
    }

    public String set(String token, Instant expiresAt) {
        long maxAge = Math.max(0, Duration.between(clock.instant(), expiresAt).getSeconds());
        return cookie(token, maxAge);
    }

    public String clear() {
        return cookie("", 0);
    }

    private String cookie(String value, long maxAge) {
        return ResponseCookie.from(NAME, value).httpOnly(true).secure(secure).sameSite("Strict").path(PATH)
                .maxAge(maxAge).build().toString();
    }

    public static String read(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return null;
        }
        for (Cookie c : request.getCookies()) {
            if (NAME.equals(c.getName())) {
                return c.getValue();
            }
        }
        return null;
    }

    public static void requireClientHeader(HttpServletRequest request) {
        if (request.getHeader(CLIENT_HEADER) == null) {
            throw new ApiException(HttpStatus.FORBIDDEN, "CLIENT_HEADER_REQUIRED",
                    "Missing " + CLIENT_HEADER + " header");
        }
    }

    public static String header() {
        return HttpHeaders.SET_COOKIE;
    }
}
