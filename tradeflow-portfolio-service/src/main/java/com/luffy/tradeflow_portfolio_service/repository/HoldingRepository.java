package com.luffy.tradeflow_portfolio_service.repository;

import com.luffy.tradeflow_portfolio_service.domain.Holding;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface HoldingRepository extends JpaRepository<Holding, Long> {

    Optional<Holding> findByUserIdAndSymbol(long userId, String symbol);

    List<Holding> findByUserIdOrderBySymbolAsc(long userId);
}
