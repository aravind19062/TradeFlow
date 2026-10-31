package com.luffy.tradeflow_live_trading_service.orders;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface LiveOrderRepository extends JpaRepository<LiveOrder, String> {

    List<LiveOrder> findByUserIdOrderByCreatedAtDesc(long userId);

    Optional<LiveOrder> findByIdAndUserId(String id, long userId);

    Optional<LiveOrder> findByUserIdAndClientOrderId(long userId, String clientOrderId);

    List<LiveOrder> findByUserIdAndCreatedAtAfter(long userId, Instant since);
}
