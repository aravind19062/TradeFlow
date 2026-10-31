package com.luffy.tradeflow_learning_service.readiness;

import com.luffy.tradeflow.common.events.Events;
import com.luffy.tradeflow.common.web.ApiException;
import com.luffy.tradeflow_learning_service.academy.Catalog;
import com.luffy.tradeflow_learning_service.academy.ContentCatalog;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Practical exercises. A user starts an exercise explicitly; it completes only when DEMO order events
 * show the required action happened after the start. The rules look at what was done (order type,
 * side, quantity, cancellation, acceptance), never at profit, trade counts or volume, and each needs
 * one deliberate action.
 */
@Service
public class ExerciseService {

    /** The cheapest demo instrument, so any sized position is affordable with the virtual cash. */
    static final String SIZING_SYMBOL = "GOLDBEES";
    static final Duration SCENARIO_VALIDITY = Duration.ofDays(7);

    private final ExerciseProgressRepository progress;
    private final ObservedOrderRepository orders;
    private final PositionSizingScenarioRepository scenarios;
    private final ContentCatalog catalog;
    private final ReadinessService readiness;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public ExerciseService(ExerciseProgressRepository progress, ObservedOrderRepository orders,
                           PositionSizingScenarioRepository scenarios, ContentCatalog catalog,
                           ReadinessService readiness, Clock clock) {
        this.progress = progress;
        this.orders = orders;
        this.scenarios = scenarios;
        this.catalog = catalog;
        this.readiness = readiness;
        this.clock = clock;
    }

    @Transactional
    public void start(long userId, String slug) {
        exercise(slug);
        ExerciseProgress.Key key = new ExerciseProgress.Key(userId, slug);
        if (!progress.existsById(key)) {
            progress.save(new ExerciseProgress(key, clock.instant()));
        }
        readiness.reconcile(userId);
    }

    public record ScenarioView(UUID id, String symbol, BigDecimal accountValue, BigDecimal riskPct,
                               BigDecimal entryPrice, BigDecimal stopPrice, Instant expiresAt, boolean answeredCorrectly,
                               Long requiredQuantity, int answerAttempts) {
        static ScenarioView of(PositionSizingScenario s) {
            boolean ok = s.getAnsweredCorrectlyAt() != null;
            return new ScenarioView(s.getId(), s.getSymbol(), s.getAccountValue(),
                    BigDecimal.valueOf(s.getRiskBps(), 2), s.getEntryPrice(), s.getStopPrice(), s.getExpiresAt(), ok,
                    ok ? s.getExpectedQuantity() : null, s.getAnswerAttempts());
        }
    }

    /** The active position-sizing scenario, issuing a new one if there is none (starts the exercise). */
    @Transactional
    public ScenarioView scenario(long userId) {
        start(userId, "position-sizing");
        Instant now = clock.instant();
        Optional<PositionSizingScenario> current = scenarios.findFirstByUserIdOrderByIssuedAtDesc(userId)
                .filter(s -> s.active(now));
        if (current.isPresent()) {
            return ScenarioView.of(current.get());
        }
        BigDecimal account = BigDecimal.valueOf(new int[]{100_000, 200_000, 300_000}[random.nextInt(3)]);
        int riskBps = new int[]{50, 100}[random.nextInt(2)];
        BigDecimal entry = BigDecimal.valueOf(5000 + random.nextInt(81) * 50L, 2); // 50.00 … 90.00
        BigDecimal distance = new BigDecimal[]{new BigDecimal("2.00"), new BigDecimal("2.50"), new BigDecimal("4.00"),
                new BigDecimal("5.00")}[random.nextInt(4)];
        return ScenarioView.of(scenarios.save(new PositionSizingScenario(userId, SIZING_SYMBOL, account, riskBps, entry,
                entry.subtract(distance), now, now.plus(SCENARIO_VALIDITY))));
    }

    public record AnswerResult(boolean correct, String explanation, ScenarioView scenario) {
    }

    @Transactional
    public AnswerResult answer(long userId, long quantity) {
        PositionSizingScenario s = scenarios.findFirstByUserIdOrderByIssuedAtDesc(userId)
                .filter(x -> x.active(clock.instant()))
                .orElseThrow(() -> ApiException.conflict("NO_ACTIVE_SCENARIO", "Get a scenario first"));
        boolean correct = s.answer(quantity, clock.instant());
        String explanation = correct
                ? "Correct. Now place a demo BUY of exactly " + s.getExpectedQuantity() + " " + s.getSymbol()
                + " shares (market or limit). Whether it makes money does not matter."
                : "Not quite. Amount at risk = account × risk %; risk per share = entry − stop; quantity = amount at risk ÷ "
                + "risk per share, rounded down.";
        return new AnswerResult(correct, explanation, ScenarioView.of(s));
    }

    // ------------------------------------------------------------------ evidence from DEMO events

    @Transactional(propagation = Propagation.MANDATORY)
    public void onPlaced(Events.OrderPlaced e, Instant at) {
        ObservedOrder o = observed(e.orderId(), e.userId());
        o.placed(e.symbol(), e.side().name(), e.type().name(), e.quantity(), at);
        evaluate(o);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void onReserved(Events.OrderReserved e, Instant at) {
        ObservedOrder o = observed(e.orderId(), e.userId());
        o.reserved(at);
        evaluate(o);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void onCancelled(Events.OrderCancelled e, Instant at) {
        ObservedOrder o = observed(e.orderId(), e.userId());
        o.cancelled(e.reason(), at);
        evaluate(o);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void onExecuted(Events.TradeExecuted e, Instant at) {
        ObservedOrder o = observed(e.orderId(), e.userId());
        o.filled(at);
        evaluate(o);
    }

    private ObservedOrder observed(UUID orderId, long userId) {
        return orders.findById(orderId).orElseGet(() -> orders.save(new ObservedOrder(orderId, userId, clock.instant())));
    }

    private void evaluate(ObservedOrder o) {
        if (!o.isPlaced()) {
            return; // wait until we know what kind of order it is
        }
        Map<String, ExerciseProgress> started = progress.findByIdUserId(o.getUserId()).stream()
                .filter(p -> p.getCompletedAt() == null)
                .collect(Collectors.toMap(p -> p.getId().getSlug(), Function.identity()));
        boolean changed = false;
        for (Catalog.Exercise ex : catalog.content().exercises()) {
            ExerciseProgress p = started.get(ex.slug());
            if (p == null || o.getPlacedAt().isBefore(p.getStartedAt())) {
                continue;
            }
            if (satisfies(ex.rule(), o)) {
                changed |= p.complete("order " + o.getOrderId() + " (" + o.getOrderType() + " " + o.getSide() + " "
                        + o.getQuantity() + " " + o.getSymbol() + ")", clock.instant());
            }
        }
        if (changed) {
            readiness.reconcile(o.getUserId());
        }
    }

    private boolean satisfies(String rule, ObservedOrder o) {
        return switch (rule) {
            case "MARKET_BUY_FILLED" -> "MARKET".equals(o.getOrderType()) && "BUY".equals(o.getSide()) && o.getFilledAt() != null;
            case "LIMIT_ORDER_CANCELLED" -> "LIMIT".equals(o.getOrderType()) && o.getCancelledAt() != null
                    && o.getCancelReason() != null && o.getCancelReason().startsWith("Cancelled by user");
            case "SELL_STOP_ACCEPTED" -> ("STOP".equals(o.getOrderType()) || "STOP_LIMIT".equals(o.getOrderType()))
                    && "SELL".equals(o.getSide()) && o.getReservedAt() != null;
            case "POSITION_SIZE_MATCHED" -> scenarios.findFirstByUserIdOrderByIssuedAtDesc(o.getUserId())
                    .filter(s -> s.getAnsweredCorrectlyAt() != null && !o.getPlacedAt().isBefore(s.getAnsweredCorrectlyAt())
                            && o.getPlacedAt().isBefore(s.getExpiresAt()))
                    .map(s -> "BUY".equals(o.getSide()) && s.getSymbol().equals(o.getSymbol())
                            && o.getQuantity() != null && o.getQuantity() == s.getExpectedQuantity() && o.getReservedAt() != null)
                    .orElse(false);
            default -> false;
        };
    }

    private Catalog.Exercise exercise(String slug) {
        return catalog.content().exercises().stream().filter(e -> e.slug().equals(slug)).findFirst()
                .orElseThrow(() -> ApiException.notFound("Exercise " + slug));
    }
}
