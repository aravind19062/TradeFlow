package com.luffy.tradeflow_live_trading_service.risk;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface RiskLimitRepository extends JpaRepository<RiskLimit, Long> {

    /** Serialises a user's order placements so daily limits cannot be raced. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RiskLimit r where r.userId = :userId")
    Optional<RiskLimit> lock(long userId);
}
