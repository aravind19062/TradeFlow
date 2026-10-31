package com.luffy.tradeflow_live_trading_service.notifications;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "live_notifications")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LiveNotification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "user_id", nullable = false)
    private long userId;
    @Column(nullable = false)
    private String kind;
    @Column(nullable = false)
    private String message;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "read_at")
    private Instant readAt;

    LiveNotification(long userId, String kind, String message, Instant now) {
        this.userId = userId;
        this.kind = kind;
        this.message = message;
        this.createdAt = now;
    }

    void markRead(Instant now) {
        if (readAt == null) {
            readAt = now;
        }
    }
}
