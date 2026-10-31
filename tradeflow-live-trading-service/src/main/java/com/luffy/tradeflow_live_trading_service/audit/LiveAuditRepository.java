package com.luffy.tradeflow_live_trading_service.audit;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LiveAuditRepository extends JpaRepository<LiveAuditEvent, Long> {

    Optional<LiveAuditEvent> findFirstByUserIdOrderBySeqDesc(long userId);

    List<LiveAuditEvent> findByUserIdOrderBySeqAsc(long userId);

    List<LiveAuditEvent> findByUserIdOrderBySeqDesc(long userId, Pageable page);
}
