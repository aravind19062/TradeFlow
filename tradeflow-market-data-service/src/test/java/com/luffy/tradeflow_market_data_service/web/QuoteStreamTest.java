package com.luffy.tradeflow_market_data_service.web;

import com.luffy.tradeflow_market_data_service.service.QuoteService;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QuoteStreamTest {

    static class Recording extends SseEmitter {
        final List<SseEventBuilder> sent = new ArrayList<>();
        boolean broken;

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            if (broken) {
                throw new IOException("browser gone");
            }
            sent.add(builder);
        }
    }

    private final QuoteService quotes = mock(QuoteService.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-09T00:00:00Z"), ZoneOffset.UTC);
    private final List<Recording> created = new ArrayList<>();
    private final QuoteStream stream = new QuoteStream(quotes, clock, () -> {
        Recording r = new Recording();
        created.add(r);
        return r;
    });

    @Test
    void sendsWhereTheStreamStartsThenOnlyNewTicks() {
        when(quotes.currentTick()).thenReturn(10L);
        stream.connect();
        assertThat(created.get(0).sent).hasSize(1);

        stream.poll();
        int afterFirstPoll = created.get(0).sent.size();
        stream.poll();
        assertThat(created.get(0).sent).hasSize(afterFirstPoll);

        when(quotes.currentTick()).thenReturn(11L);
        stream.poll();
        assertThat(created.get(0).sent).hasSize(afterFirstPoll + 1);
    }

    @Test
    void dropsClientsThatHaveGone() {
        when(quotes.currentTick()).thenReturn(10L);
        stream.connect();
        created.get(0).broken = true;
        when(quotes.currentTick()).thenReturn(11L);
        stream.poll();
        assertThat(stream.clientCount()).isZero();
    }

    @Test
    void refusesConnectionsOverTheLimit() {
        when(quotes.currentTick()).thenReturn(1L);
        for (int i = 0; i < QuoteStream.MAX_CLIENTS; i++) {
            assertThat(stream.connect()).isNotNull();
        }
        assertThat(stream.connect()).isNull();
    }

    @Test
    void doesNoDatabaseWorkWithoutClients() {
        stream.poll();
        org.mockito.Mockito.verifyNoInteractions(quotes);
    }
}
