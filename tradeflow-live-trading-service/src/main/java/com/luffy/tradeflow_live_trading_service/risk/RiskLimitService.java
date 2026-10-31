package com.luffy.tradeflow_live_trading_service.risk;

import com.luffy.tradeflow.common.web.ApiException;
import com.luffy.tradeflow_live_trading_service.config.LiveProperties;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Pre-trade limits. Platform values are ceilings; a user may only set stricter ones. Raising a
 * user limit (back towards the ceiling) is a risk-increasing change and needs a step-up, which the
 * controller enforces via {@link #isLoosening}.
 */
@Service
public class RiskLimitService {

    /** Trading day boundaries follow the Indian exchanges. */
    public static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Kolkata");

    public record Limits(BigDecimal maxOrderValue, int maxOrdersPerDay, BigDecimal maxDailyTurnover) {
    }

    public record View(Limits effective, Limits platform, boolean customised) {
    }

    private final RiskLimitRepository repository;
    private final LiveProperties properties;
    private final TransactionTemplate newTx;
    private final Clock clock;

    public RiskLimitService(RiskLimitRepository repository, LiveProperties properties, PlatformTransactionManager tx,
                            Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.newTx = new TransactionTemplate(tx);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    public Limits platform() {
        return new Limits(properties.platformMaxOrderValue(), properties.platformMaxOrdersPerDay(),
                properties.platformMaxDailyTurnover());
    }

    @Transactional(readOnly = true)
    public View view(long userId) {
        var row = repository.findById(userId);
        return new View(row.map(this::effective).orElse(platform()), platform(), row.isPresent());
    }

    /** Effective limits under a row lock held until the caller's transaction ends. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public Limits lockAndGet(long userId) {
        ensureRow(userId);
        return effective(repository.lock(userId).orElseThrow());
    }

    public boolean isLoosening(long userId, Limits requested) {
        Limits current = view(userId).effective();
        return requested.maxOrderValue().compareTo(current.maxOrderValue()) > 0
                || requested.maxOrdersPerDay() > current.maxOrdersPerDay()
                || requested.maxDailyTurnover().compareTo(current.maxDailyTurnover()) > 0;
    }

    /** Range checks, done before deciding whether a step-up is needed. */
    public void validate(Limits requested) {
        Limits p = platform();
        if (requested.maxOrderValue() == null || requested.maxOrderValue().signum() <= 0
                || requested.maxOrderValue().compareTo(p.maxOrderValue()) > 0) {
            throw ApiException.unprocessable("LIMIT_OUT_OF_RANGE", "Max order value must be above 0 and at most "
                    + p.maxOrderValue().toPlainString());
        }
        if (requested.maxOrdersPerDay() < 1 || requested.maxOrdersPerDay() > p.maxOrdersPerDay()) {
            throw ApiException.unprocessable("LIMIT_OUT_OF_RANGE", "Orders per day must be between 1 and "
                    + p.maxOrdersPerDay());
        }
        if (requested.maxDailyTurnover() == null || requested.maxDailyTurnover().compareTo(requested.maxOrderValue()) < 0
                || requested.maxDailyTurnover().compareTo(p.maxDailyTurnover()) > 0) {
            throw ApiException.unprocessable("LIMIT_OUT_OF_RANGE", "Daily turnover must be at least the max order value "
                    + "and at most " + p.maxDailyTurnover().toPlainString());
        }
    }

    @Transactional
    public View update(long userId, Limits requested) {
        validate(requested);
        Instant now = clock.instant();
        RiskLimit row = repository.findById(userId).orElseGet(() -> new RiskLimit(userId, requested.maxOrderValue(),
                requested.maxOrdersPerDay(), requested.maxDailyTurnover(), now));
        row.set(requested.maxOrderValue(), requested.maxOrdersPerDay(), requested.maxDailyTurnover(), now);
        repository.save(row);
        return view(userId);
    }

    public Instant startOfTradingDay() {
        return LocalDate.now(clock.withZone(MARKET_ZONE)).atStartOfDay(MARKET_ZONE).toInstant();
    }

    private Limits effective(RiskLimit r) {
        Limits p = platform();
        return new Limits(r.getMaxOrderValue().min(p.maxOrderValue()), Math.min(r.getMaxOrdersPerDay(), p.maxOrdersPerDay()),
                r.getMaxDailyTurnover().min(p.maxDailyTurnover()));
    }

    private void ensureRow(long userId) {
        if (repository.existsById(userId)) {
            return;
        }
        try {
            Limits p = platform();
            newTx.executeWithoutResult(s -> repository.saveAndFlush(new RiskLimit(userId, p.maxOrderValue(),
                    p.maxOrdersPerDay(), p.maxDailyTurnover(), clock.instant())));
        } catch (DataIntegrityViolationException raced) {
            // another request created it first
        }
    }
}
