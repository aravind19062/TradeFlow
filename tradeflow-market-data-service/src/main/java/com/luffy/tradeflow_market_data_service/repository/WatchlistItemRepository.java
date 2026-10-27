package com.luffy.tradeflow_market_data_service.repository;

import com.luffy.tradeflow_market_data_service.domain.WatchlistItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WatchlistItemRepository extends JpaRepository<WatchlistItem, Long> {

    List<WatchlistItem> findByUserIdOrderByAddedAtAsc(long userId);

    Optional<WatchlistItem> findByUserIdAndSymbol(long userId, String symbol);

    long countByUserId(long userId);
}
