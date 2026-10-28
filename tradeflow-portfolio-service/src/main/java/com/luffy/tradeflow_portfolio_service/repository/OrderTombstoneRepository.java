package com.luffy.tradeflow_portfolio_service.repository;

import com.luffy.tradeflow_portfolio_service.domain.OrderTombstone;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface OrderTombstoneRepository extends JpaRepository<OrderTombstone, UUID> {
}
