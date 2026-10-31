package com.luffy.tradeflow_learning_service.readiness;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ReadinessStatusRepository extends JpaRepository<ReadinessStatus, Long> {

    /** Serialises all readiness changes of one user (and therefore the audit chain's sequence). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ReadinessStatus s where s.userId = :userId")
    Optional<ReadinessStatus> lock(long userId);

    List<ReadinessStatus> findByState(ReadinessState state);

    List<ReadinessStatus> findByStateAndExpiresAtBefore(ReadinessState state, Instant now);
}
