package com.luffy.tradeflow_market_data_service.repository;

import com.luffy.tradeflow_market_data_service.domain.PriceTick;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface PriceTickRepository extends JpaRepository<PriceTick, Long> {

    List<PriceTick> findByTickIn(Collection<Long> ticks);

    List<PriceTick> findBySymbolInAndTickIn(Collection<String> symbols, Collection<Long> ticks);

    List<PriceTick> findBySymbolOrderByTickDesc(String symbol, Limit limit);

    List<PriceTick> findBySymbolAndAsOfGreaterThanEqualOrderByTickAsc(String symbol, Instant since, Limit limit);
}
