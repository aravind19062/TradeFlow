package com.luffy.tradeflow_live_trading_service.orders;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface LiveOrderRepository extends JpaRepository<LiveOrder, String> {

    List<LiveOrder> findByUserIdOrderByCreatedAtDesc(long userId);

    Optional<LiveOrder> findByIdAndUserId(String id, long userId);

    Optional<LiveOrder> findByUserIdAndClientOrderId(long userId, String clientOrderId);

    List<LiveOrder> findByUserIdAndCreatedAtAfter(long userId, Instant since);

    List<LiveOrder> findByUserIdAndStatusIn(long userId, Collection<String> statuses);

    boolean existsByUserIdAndStatusIn(long userId, Collection<String> statuses);

    @Query("select distinct o.userId from LiveOrder o where o.status in :statuses")
    List<Long> usersWithStatusIn(@Param("statuses") Collection<String> statuses);
}
