package com.luffy.tradeflow_user_service.passkey;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserPasskeyRepository extends JpaRepository<UserPasskey, UUID> {
    List<UserPasskey> findByUserIdOrderByCreatedAtDesc(long userId);

    Optional<UserPasskey> findByCredentialId(String credentialId);

    Optional<UserPasskey> findByIdAndUserId(UUID id, long userId);

    long countByUserId(long userId);
}
