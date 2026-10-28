package com.luffy.tradeflow.common.outbox;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    List<OutboxEvent> findTop100ByPublishedAtIsNullOrderByIdAsc();

    /**
     * Claims the next unpublished rows with SELECT ... FOR UPDATE SKIP LOCKED, so several
     * instances of a service can publish concurrently without sending the same row twice
     * (a lock timeout of -2 is the JPA hint Hibernate renders as SKIP LOCKED).
     */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.QueryHints(
            @jakarta.persistence.QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @org.springframework.data.jpa.repository.Query("select e from OutboxEvent e where e.publishedAt is null order by e.id")
    List<OutboxEvent> claimNextBatch(org.springframework.data.domain.Limit limit);

    long countByPublishedAtIsNull();

    Optional<OutboxEvent> findFirstByPublishedAtIsNullOrderByIdAsc();
}
