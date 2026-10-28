package com.luffy.tradeflow_portfolio_service.repository;

import com.luffy.tradeflow_portfolio_service.domain.Reservation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ReservationRepository extends JpaRepository<Reservation, UUID> {

    /**
     * SELECT ... FOR UPDATE: serializes concurrent settlements of the same order, so a
     * duplicate trade delivery waits for the first one and then sees it as booked.
     */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select r from Reservation r where r.orderId = :orderId")
    java.util.Optional<Reservation> findForUpdate(@org.springframework.data.repository.query.Param("orderId") UUID orderId);

    List<Reservation> findByUserIdAndStatus(long userId, Reservation.Status status);

    List<Reservation> findByStatusAndCreatedAtBefore(Reservation.Status status, Instant before);
}
