package com.luffy.tradeflow_portfolio_service.repository;

import com.luffy.tradeflow_portfolio_service.domain.Account;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountRepository extends JpaRepository<Account, Long> {

    /**
     * SELECT ... FOR UPDATE on the user's account: serializes reserve and release for the
     * same user, which arrive on different topics and may otherwise interleave.
     */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select a from Account a where a.userId = :userId")
    java.util.Optional<Account> findForUpdate(@org.springframework.data.repository.query.Param("userId") Long userId);
}
