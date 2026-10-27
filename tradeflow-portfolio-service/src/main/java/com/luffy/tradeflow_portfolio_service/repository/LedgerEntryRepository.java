package com.luffy.tradeflow_portfolio_service.repository;

import com.luffy.tradeflow_portfolio_service.domain.LedgerEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {

    List<LedgerEntry> findByUserIdOrderByOccurredAtAscIdAsc(long userId);

    Page<LedgerEntry> findByUserId(long userId, Pageable pageable);

    Page<LedgerEntry> findByUserIdAndType(long userId, LedgerEntry.Type type, Pageable pageable);

    Optional<LedgerEntry> findFirstByUserIdOrderByIdDesc(long userId);

    boolean existsByTradeId(UUID tradeId);
}
