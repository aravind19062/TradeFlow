package com.luffy.tradeflow_user_service.security;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    /** Row lock so two concurrent refreshes with the same token cannot both rotate it. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    @Modifying
    @Query("update RefreshToken t set t.revokedAt = :at, t.revokeReason = :reason "
            + "where t.familyId = :family and t.revokedAt is null")
    int revokeFamily(UUID family, Instant at, String reason);

    @Modifying
    @Query("update RefreshToken t set t.revokedAt = :at, t.revokeReason = :reason "
            + "where t.userId = :userId and t.revokedAt is null")
    int revokeAllForUser(long userId, Instant at, String reason);
}
