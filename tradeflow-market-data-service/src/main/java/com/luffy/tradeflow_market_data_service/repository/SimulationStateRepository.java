package com.luffy.tradeflow_market_data_service.repository;

import com.luffy.tradeflow_market_data_service.domain.SimulationState;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface SimulationStateRepository extends JpaRepository<SimulationState, Integer> {

    /** SELECT ... FOR UPDATE: serializes tick generation between the scheduler and the admin endpoint. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from SimulationState s where s.id = " + SimulationState.SINGLETON_ID)
    Optional<SimulationState> lockState();

    @Query("select s from SimulationState s where s.id = " + SimulationState.SINGLETON_ID)
    Optional<SimulationState> readState();
}
