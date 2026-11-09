package com.luffy.tradeflow_user_service.passkey;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.UUID;

public interface PasskeyChallengeRepository extends JpaRepository<PasskeyChallenge, UUID> {
    /** Atomic, so one challenge can be redeemed only once even by concurrent requests. */
    @Modifying
    @Query("update PasskeyChallenge c set c.usedAt = :now where c.id = :id and c.purpose = :purpose "
            + "and c.usedAt is null and c.expiresAt > :now")
    int redeem(UUID id, String purpose, Instant now);

    @Modifying
    @Query("delete from PasskeyChallenge c where c.expiresAt < :now")
    int deleteExpired(Instant now);

    long countByExpiresAtAfter(Instant now);
}
