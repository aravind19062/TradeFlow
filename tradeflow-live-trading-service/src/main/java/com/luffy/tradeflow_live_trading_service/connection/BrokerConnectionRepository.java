package com.luffy.tradeflow_live_trading_service.connection;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BrokerConnectionRepository extends JpaRepository<BrokerConnection, UUID> {

    List<BrokerConnection> findByUserIdOrderByCreatedAtDesc(long userId);

    Optional<BrokerConnection> findFirstByUserIdAndStatusOrderByConnectedAtDesc(long userId, BrokerConnection.Status status);
}
