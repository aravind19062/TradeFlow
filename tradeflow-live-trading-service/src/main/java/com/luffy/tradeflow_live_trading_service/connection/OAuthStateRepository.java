package com.luffy.tradeflow_live_trading_service.connection;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface OAuthStateRepository extends JpaRepository<OAuthState, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from OAuthState s where s.stateHash = :hash")
    Optional<OAuthState> lock(String hash);
}
