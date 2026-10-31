package com.luffy.tradeflow_user_service.security;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface RecoveryCodeRepository extends JpaRepository<RecoveryCode, Long> {

    List<RecoveryCode> findByUserIdAndUsedAtIsNull(long userId);

    long countByUserIdAndUsedAtIsNull(long userId);

    @Modifying
    @Query("delete from RecoveryCode c where c.userId = :userId")
    void deleteAllForUser(long userId);
}
