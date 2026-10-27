package com.luffy.tradeflow_market_data_service.service;

import com.luffy.tradeflow.common.money.Money;
import com.luffy.tradeflow.common.web.ApiException;
import com.luffy.tradeflow_market_data_service.domain.PriceAlert;
import com.luffy.tradeflow_market_data_service.repository.PriceAlertRepository;
import com.luffy.tradeflow_market_data_service.web.MarketDtos.AlertRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

@Service
public class AlertService {

    public static final int MAX_ACTIVE_ALERTS = 100;

    private final PriceAlertRepository repository;
    private final QuoteService quoteService;
    private final Clock clock;

    public AlertService(PriceAlertRepository repository, QuoteService quoteService, Clock clock) {
        this.repository = repository;
        this.quoteService = quoteService;
        this.clock = clock;
    }

    @Transactional
    public PriceAlert create(long userId, AlertRequest request) {
        String symbol = quoteService.instrument(request.symbol()).getSymbol();
        if (repository.countByUserIdAndStatus(userId, PriceAlert.Status.ACTIVE) >= MAX_ACTIVE_ALERTS) {
            throw ApiException.unprocessable("TOO_MANY_ALERTS", "At most " + MAX_ACTIVE_ALERTS + " active alerts");
        }
        return repository.save(new PriceAlert(userId, symbol, request.direction(), Money.price(request.threshold()),
                request.note(), clock.instant()));
    }

    @Transactional(readOnly = true)
    public List<PriceAlert> list(long userId, PriceAlert.Status status) {
        return status == null
                ? repository.findByUserIdOrderByCreatedAtDesc(userId)
                : repository.findByUserIdAndStatusOrderByCreatedAtDesc(userId, status);
    }

    /** Another user's alert is reported as not found, so ids cannot be probed. */
    @Transactional
    public PriceAlert cancel(long userId, UUID alertId) {
        PriceAlert alert = repository.findByIdAndUserId(alertId, userId)
                .orElseThrow(() -> ApiException.notFound("Alert " + alertId));
        alert.cancel();
        return alert;
    }
}
