package com.luffy.tradeflow_market_data_service.repository;

import com.luffy.tradeflow_market_data_service.domain.Instrument;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InstrumentRepository extends JpaRepository<Instrument, String> {
}
