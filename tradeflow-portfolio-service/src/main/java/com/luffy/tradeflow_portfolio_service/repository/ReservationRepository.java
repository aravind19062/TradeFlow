package com.luffy.tradeflow_portfolio_service.repository;

import com.luffy.tradeflow_portfolio_service.domain.Reservation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ReservationRepository extends JpaRepository<Reservation, UUID> {

    List<Reservation> findByUserIdAndStatus(long userId, Reservation.Status status);

    List<Reservation> findByStatusAndCreatedAtBefore(Reservation.Status status, Instant before);
}
