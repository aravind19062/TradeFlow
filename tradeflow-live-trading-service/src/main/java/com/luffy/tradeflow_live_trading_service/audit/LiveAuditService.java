package com.luffy.tradeflow_live_trading_service.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Clock;
import java.util.List;

/**
 * Records every live action and every refusal, in its own transaction so a refusal is kept even
 * though the request fails. Details must never contain tokens or credentials. User id 0 is the
 * platform-wide chain (global kill switch).
 */
@Service
public class LiveAuditService {

    public static final long PLATFORM = 0L;
    private static final Logger log = LoggerFactory.getLogger(LiveAuditService.class);

    public enum Outcome { ALLOWED, REFUSED, SUBMITTED, REJECTED, UNKNOWN, FAILED, INFO }

    public record ChainCheck(boolean intact, int events, Integer firstBrokenSeq) {
    }

    private final LiveAuditRepository repository;
    private final TransactionTemplate tx;
    private final Clock clock;

    public LiveAuditService(LiveAuditRepository repository, PlatformTransactionManager txManager, Clock clock) {
        this.repository = repository;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    public void record(long userId, String action, Outcome outcome, String detail, long actorId) {
        String ip = clientIp();
        for (int attempt = 1; ; attempt++) {
            try {
                tx.executeWithoutResult(s -> {
                    var last = repository.findFirstByUserIdOrderBySeqDesc(userId);
                    repository.save(new LiveAuditEvent(userId, last.map(e -> e.getSeq() + 1).orElse(1), action,
                            outcome.name(), detail, actorId, ip, clock.instant(),
                            last.map(LiveAuditEvent::getHash).orElse(LiveAuditEvent.GENESIS)));
                });
                log.info("live-audit user={} action={} outcome={}", userId, action, outcome);
                return;
            } catch (DataIntegrityViolationException e) {
                if (attempt >= 5) {
                    throw e; // concurrent writers kept colliding on the sequence number
                }
            }
        }
    }

    public List<LiveAuditEvent> recent(long userId, int limit) {
        return repository.findByUserIdOrderBySeqDesc(userId, PageRequest.of(0, limit));
    }

    public ChainCheck verify(long userId) {
        List<LiveAuditEvent> events = repository.findByUserIdOrderBySeqAsc(userId);
        String prev = LiveAuditEvent.GENESIS;
        int expectedSeq = 1;
        for (LiveAuditEvent e : events) {
            if (e.getSeq() != expectedSeq || !e.getPrevHash().equals(prev) || !e.getHash().equals(e.computeHash())) {
                return new ChainCheck(false, events.size(), e.getSeq());
            }
            prev = e.getHash();
            expectedSeq++;
        }
        return new ChainCheck(true, events.size(), null);
    }

    private static String clientIp() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return attrs.getRequest().getRemoteAddr();
        }
        return null;
    }
}
