package com.luffy.tradeflow_user_service.service;

import com.luffy.tradeflow.common.web.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Brute-force protection for POST /api/auth/login. Failed attempts are counted in a
 * sliding window per account (email) and per client address; once either limit is
 * reached, further attempts get HTTP 429 with Retry-After until the oldest failure
 * leaves the window. A successful login clears the account's counter.
 *
 * <p>State is in memory, so limits are per instance and reset on restart. A shared
 * store (e.g. Redis) would be needed for strict limits across several instances.
 */
@Service
public class LoginAttemptService {

    private final Map<String, Deque<Instant>> accountFailures = new ConcurrentHashMap<>();
    private final Map<String, Deque<Instant>> clientFailures = new ConcurrentHashMap<>();
    private final int maxPerAccount;
    private final int maxPerClient;
    private final Duration window;
    private final Clock clock;

    public LoginAttemptService(@Value("${tradeflow.security.login.max-failures-per-account:5}") int maxPerAccount,
                               @Value("${tradeflow.security.login.max-failures-per-client:20}") int maxPerClient,
                               @Value("${tradeflow.security.login.window:PT15M}") Duration window, Clock clock) {
        this.maxPerAccount = maxPerAccount;
        this.maxPerClient = maxPerClient;
        this.window = window;
        this.clock = clock;
    }

    /** Throws 429 if this account or client is currently locked out. */
    public void checkAllowed(String email, String client) {
        Instant now = clock.instant();
        long account = retryAfter(accountFailures.get(email), maxPerAccount, now);
        long perClient = retryAfter(clientFailures.get(client), maxPerClient, now);
        long wait = Math.max(account, perClient);
        if (wait > 0) {
            throw ApiException.tooManyRequests("TOO_MANY_LOGIN_ATTEMPTS",
                    "Too many failed login attempts; try again in " + wait + " seconds", wait);
        }
    }

    public void recordFailure(String email, String client) {
        Instant now = clock.instant();
        add(accountFailures, email, now);
        add(clientFailures, client, now);
    }

    public void recordSuccess(String email) {
        accountFailures.remove(email);
    }

    /** Drops expired entries so memory stays bounded by recent activity. */
    @Scheduled(fixedDelay = 60_000)
    public void evictExpired() {
        Instant cutoff = clock.instant().minus(window);
        for (Map<String, Deque<Instant>> map : java.util.List.of(accountFailures, clientFailures)) {
            map.entrySet().removeIf(e -> {
                synchronized (e.getValue()) {
                    prune(e.getValue(), cutoff);
                    return e.getValue().isEmpty();
                }
            });
        }
    }

    private void add(Map<String, Deque<Instant>> map, String key, Instant now) {
        Deque<Instant> q = map.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (q) {
            prune(q, now.minus(window));
            q.addLast(now);
        }
    }

    private long retryAfter(Deque<Instant> q, int limit, Instant now) {
        if (q == null) {
            return 0;
        }
        synchronized (q) {
            prune(q, now.minus(window));
            if (q.size() < limit) {
                return 0;
            }
            Instant unlock = q.peekFirst().plus(window);
            return Math.max(1, Duration.between(now, unlock).toSeconds());
        }
    }

    private static void prune(Deque<Instant> q, Instant cutoff) {
        while (!q.isEmpty() && !q.peekFirst().isAfter(cutoff)) {
            q.pollFirst();
        }
    }
}
