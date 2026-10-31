package com.luffy.tradeflow_live_trading_service.access;

import org.springframework.data.jpa.repository.JpaRepository;

public interface KillSwitchRepository extends JpaRepository<KillSwitch, KillSwitch.Key> {
}
