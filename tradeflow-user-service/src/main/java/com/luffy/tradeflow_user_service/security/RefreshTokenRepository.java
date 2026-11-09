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

    /** Tokens of a session issued at or after a moment (the successors of a rotated token). */
    java.util.List<RefreshToken> findByFamilyIdAndIssuedAtGreaterThanEqual(UUID familyId, Instant since);

    /** The live link of each of a user's sessions. */
    @Query("select t from RefreshToken t where t.userId = :userId and t.tokenVersion = :tokenVersion "
            + "and t.revokedAt is null and t.usedAt is null and t.expiresAt > :now and t.sessionExpiresAt > :now")
    java.util.List<RefreshToken> findLive(long userId, int tokenVersion, Instant now);

    @Query("select t.familyId, min(t.issuedAt) from RefreshToken t where t.familyId in :families group by t.familyId")
    java.util.List<Object[]> sessionStarts(java.util.Collection<UUID> families);

    boolean existsByFamilyIdAndUserId(UUID familyId, long userId);

    @Modifying
    @Query("update RefreshToken t set t.revokedAt = :at, t.revokeReason = :reason "
            + "where t.familyId = :family and t.revokedAt is null")
    int revokeFamily(UUID family, Instant at, String reason);

    @Modifying
    @Query("update RefreshToken t set t.revokedAt = :at, t.revokeReason = :reason "
            + "where t.userId = :userId and t.revokedAt is null")
    int revokeAllForUser(long userId, Instant at, String reason);
}
