package com.luffy.tradeflow_market_data_service.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "simulation_state")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SimulationState {

    public static final int SINGLETON_ID = 1;

    @Id
    private Integer id;

    /** Last generated tick; -1 until the price history has been seeded. */
    @Column(name = "current_tick", nullable = false)
    private long currentTick;

    @Column(name = "updated_at")
    private Instant updatedAt;

    public boolean isSeeded() {
        return currentTick >= 0;
    }

    public void advanceTo(long tick, Instant at) {
        if (tick <= currentTick) {
            throw new IllegalStateException("Simulation clock cannot move backwards: " + currentTick + " -> " + tick);
        }
        this.currentTick = tick;
        this.updatedAt = at;
    }
}
