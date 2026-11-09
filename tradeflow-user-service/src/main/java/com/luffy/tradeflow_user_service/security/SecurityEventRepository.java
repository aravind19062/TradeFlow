package com.luffy.tradeflow_user_service.security;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SecurityEventRepository extends JpaRepository<SecurityEvent, Long> {

    List<SecurityEvent> findByUserIdOrderByOccurredAtDesc(long userId, Pageable page);

    List<SecurityEvent> findByUserIdAndTypeOrderByOccurredAtDesc(long userId, String type);

    org.springframework.data.domain.Page<SecurityEvent> findByUserId(long userId, Pageable page);

    org.springframework.data.domain.Page<SecurityEvent> findByUserIdAndTypeIn(long userId, java.util.Collection<String> types, Pageable page);
}
