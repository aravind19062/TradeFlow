package com.luffy.tradeflow_market_data_service.repository;

import com.luffy.tradeflow_market_data_service.domain.PriceAlert;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PriceAlertRepository extends JpaRepository<PriceAlert, UUID> {

    List<PriceAlert> findBySymbolInAndStatus(Collection<String> symbols, PriceAlert.Status status);

    List<PriceAlert> findByUserIdOrderByCreatedAtDesc(long userId);

    List<PriceAlert> findByUserIdAndStatusOrderByCreatedAtDesc(long userId, PriceAlert.Status status);

    Optional<PriceAlert> findByIdAndUserId(UUID id, long userId);

    long countByUserIdAndStatus(long userId, PriceAlert.Status status);
}
