package com.luffy.tradeflow_user_service.security;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;

/**
 * Writes the security audit trail. Each record commits in its own transaction, so a failed
 * login or a refused step-up is recorded even though the request itself fails.
 */
@Service
public class SecurityEventService {

    private final SecurityEventRepository repository;
    private final Clock clock;

    public SecurityEventService(SecurityEventRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(long userId, SecurityEvent.Type type, Long actorId, String detail, ClientInfo client) {
        repository.save(new SecurityEvent(userId, type, actorId, detail, client.ip(), client.userAgent(),
                clock.instant()));
    }

    /** A filtered page of one user's activity, newest first. */
    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<SecurityEvent> page(long userId, java.util.Set<String> types, int page, int size) {
        var request = PageRequest.of(Math.max(page, 0), Math.max(1, Math.min(size, 50)),
                org.springframework.data.domain.Sort.by("occurredAt", "id").descending());
        return types == null ? repository.findByUserId(userId, request)
                : repository.findByUserIdAndTypeIn(userId, types, request);
    }

    @Transactional(readOnly = true)
    public List<SecurityEvent> recent(long userId, int limit) {
        return repository.findByUserIdOrderByOccurredAtDesc(userId, PageRequest.of(0, Math.min(limit, 100)));
    }
}
