package com.luffy.tradeflow_user_service.security;

import jakarta.servlet.http.HttpServletRequest;

/** Where a security-relevant request came from, for audit records and throttling. */
public record ClientInfo(String ip, String userAgent) {

    public static ClientInfo of(HttpServletRequest request) {
        String ua = request.getHeader("User-Agent");
        return new ClientInfo(request.getRemoteAddr(), ua == null ? null : ua.substring(0, Math.min(ua.length(), 255)));
    }

    public static final ClientInfo SYSTEM = new ClientInfo(null, null);
}
