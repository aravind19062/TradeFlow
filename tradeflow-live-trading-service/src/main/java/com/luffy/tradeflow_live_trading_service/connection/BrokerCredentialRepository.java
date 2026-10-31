package com.luffy.tradeflow_live_trading_service.connection;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface BrokerCredentialRepository extends JpaRepository<BrokerCredential, UUID> {
}
