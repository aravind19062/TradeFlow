package com.luffy.tradeflow_order_service.repository;

import com.luffy.tradeflow_order_service.domain.LatestPrice;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface LatestPriceRepository extends JpaRepository<LatestPrice, String> {

    /** Locks the symbol's row, so two orders can't take the same simulated liquidity. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from LatestPrice p where p.symbol = :symbol")
    Optional<LatestPrice> findForUpdate(@Param("symbol") String symbol);
}
