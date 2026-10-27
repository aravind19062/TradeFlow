package com.luffy.tradeflow_order_service.repository;

import com.luffy.tradeflow_order_service.domain.LatestPrice;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LatestPriceRepository extends JpaRepository<LatestPrice, String> {
}
