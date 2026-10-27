package com.luffy.tradeflow_order_service.repository;

import com.luffy.tradeflow_order_service.domain.Trade;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TradeRepository extends JpaRepository<Trade, UUID> {

    List<Trade> findByOrderIdOrderByExecutedAtAsc(UUID orderId);

    Page<Trade> findByUserId(long userId, Pageable pageable);

    Page<Trade> findByUserIdAndSymbol(long userId, String symbol, Pageable pageable);
}
