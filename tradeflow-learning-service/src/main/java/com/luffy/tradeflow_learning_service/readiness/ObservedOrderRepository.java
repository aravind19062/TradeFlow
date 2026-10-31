package com.luffy.tradeflow_learning_service.readiness;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ObservedOrderRepository extends JpaRepository<ObservedOrder, UUID> {
}
