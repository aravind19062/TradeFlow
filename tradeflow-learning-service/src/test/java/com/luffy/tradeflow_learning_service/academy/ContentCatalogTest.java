package com.luffy.tradeflow_learning_service.academy;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The shipped Academy content loads and is internally consistent. */
class ContentCatalogTest {

    private final Catalog.Content content = ContentCatalog.load("classpath:academy/", JsonMapper.builder().build());

    @Test
    void coversTheTenRequiredTopics() {
        assertThat(content.modules()).extracting(Catalog.Module::slug).containsExactly(
                "stock-market-fundamentals", "market-and-limit-orders", "bid-ask-spread", "order-execution",
                "holdings-and-positions", "portfolio-management", "risk-management", "stop-loss-and-position-sizing",
                "leverage-and-margin", "common-mistakes");
        assertThat(content.modules()).allSatisfy(m -> {
            assertThat(m.mandatory()).isTrue();
            assertThat(m.lessons()).isNotEmpty().allSatisfy(l -> assertThat(l.body()).startsWith("# "));
            assertThat(m.questions()).hasSizeGreaterThanOrEqualTo(m.quiz().questionsServed());
        });
    }

    @Test
    void everyQuestionHasAValidAnswerKeyAndAnExplanation() {
        List<Catalog.Question> all = new java.util.ArrayList<>(content.finalQuestions());
        content.modules().forEach(m -> all.addAll(m.questions()));
        assertThat(all).hasSize(105).allSatisfy(q -> {
            assertThat(q.correctKeys()).isNotEmpty();
            assertThat(q.multi() || q.correctKeys().size() == 1).isTrue();
            assertThat(q.explanation()).isNotBlank();
            assertThat(q.options()).hasSizeBetween(2, 6);
        });
        assertThat(content.finalAssessment().questionsServed()).isEqualTo(20);
    }

    @Test
    void readinessNeverDependsOnProfitOrTradingVolume() {
        // The final assessment states it, and no exercise rule measures P&L or counts trades.
        assertThat(content.exercises()).extracting(Catalog.Exercise::rule).containsExactlyInAnyOrder(
                "MARKET_BUY_FILLED", "LIMIT_ORDER_CANCELLED", "SELL_STOP_ACCEPTED", "POSITION_SIZE_MATCHED");
        assertThat(content.finalQuestions()).anySatisfy(q -> assertThat(q.options())
                .anySatisfy(o -> {
                    assertThat(o.text()).isEqualTo("Having a profitable demo portfolio");
                    assertThat(o.correct()).isFalse();
                }));
    }

    @Test
    void contentHasAStableFingerprint() {
        Catalog.Content again = ContentCatalog.load("classpath:academy/", JsonMapper.builder().build());
        assertThat(again.hash()).isEqualTo(content.hash()).hasSize(64);
    }
}
