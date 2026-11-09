package com.luffy.tradeflow_market_data_service.web;

import com.luffy.tradeflow_market_data_service.service.QuoteService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * Tells connected browsers the moment a new simulated tick exists; they then re-read prices through
 * the normal APIs. Each instance watches the shared tick in the database, so this works whichever
 * replica generated the tick.
 */
@Component
@EnableScheduling
public class QuoteStream {

    private static final Logger log = LoggerFactory.getLogger(QuoteStream.class);
    static final int MAX_CLIENTS = 100;
    private static final Duration HEARTBEAT = Duration.ofSeconds(20);

    private final List<SseEmitter> clients = new CopyOnWriteArrayList<>();
    private final QuoteService quotes;
    private final Clock clock;
    private final Supplier<SseEmitter> emitters;
    private volatile long lastTick = -1;
    private volatile Instant lastSend = Instant.EPOCH;

    @org.springframework.beans.factory.annotation.Autowired
    public QuoteStream(QuoteService quotes, Clock clock) {
        this(quotes, clock, () -> new SseEmitter(0L));
    }

    QuoteStream(QuoteService quotes, Clock clock, Supplier<SseEmitter> emitters) {
        this.quotes = quotes;
        this.clock = clock;
        this.emitters = emitters;
    }

    /** Returns null when the instance already has as many streams as it allows. */
    public SseEmitter connect() {
        if (clients.size() >= MAX_CLIENTS) {
            return null;
        }
        SseEmitter emitter = emitters.get();
        clients.add(emitter);
        emitter.onCompletion(() -> clients.remove(emitter));
        emitter.onTimeout(() -> clients.remove(emitter));
        emitter.onError(e -> clients.remove(emitter));
        send(emitter, SseEmitter.event().name("tick").data(Map.of("tick", quotes.currentTick())));
        return emitter;
    }

    int clientCount() {
        return clients.size();
    }

    @Scheduled(fixedDelay = 2_000)
    void poll() {
        if (clients.isEmpty()) {
            return;
        }
        long tick;
        try {
            tick = quotes.currentTick();
        } catch (RuntimeException e) {
            log.warn("Could not read the current tick: {}", e.getMessage());
            return;
        }
        Instant now = clock.instant();
        if (tick != lastTick) {
            lastTick = tick;
            lastSend = now;
            clients.forEach(c -> send(c, SseEmitter.event().name("tick").data(Map.of("tick", tick))));
        } else if (Duration.between(lastSend, now).compareTo(HEARTBEAT) >= 0) {
            lastSend = now;
            clients.forEach(c -> send(c, SseEmitter.event().comment("keep-alive")));
        }
    }

    private void send(SseEmitter emitter, SseEmitter.SseEventBuilder event) {
        try {
            emitter.send(event);
        } catch (IOException | IllegalStateException e) {
            // The browser went away; completing the emitter is what triggers no further sends.
            clients.remove(emitter);
        }
    }
}
