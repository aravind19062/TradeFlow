package com.luffy.tradeflow_live_trading_service.notifications;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LiveNotificationRepository extends JpaRepository<LiveNotification, Long> {

    List<LiveNotification> findByUserIdOrderByCreatedAtDescIdDesc(long userId, Pageable page);

    Optional<LiveNotification> findByIdAndUserId(long id, long userId);
}
