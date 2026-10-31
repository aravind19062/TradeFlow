package com.luffy.tradeflow_live_trading_service.orders;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface OrderConfirmationRepository extends JpaRepository<OrderConfirmation, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from OrderConfirmation c where c.id = :id")
    Optional<OrderConfirmation> lock(UUID id);
}
