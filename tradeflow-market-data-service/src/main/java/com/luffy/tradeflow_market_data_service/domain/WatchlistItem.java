package com.luffy.tradeflow_market_data_service.domain;

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
@Table(name = "watchlist_items")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WatchlistItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private long userId;

    @Column(nullable = false)
    private String symbol;

    @Column(name = "added_at", nullable = false)
    private Instant addedAt;

    public WatchlistItem(long userId, String symbol, Instant addedAt) {
        this.userId = userId;
        this.symbol = symbol;
        this.addedAt = addedAt;
    }
}
