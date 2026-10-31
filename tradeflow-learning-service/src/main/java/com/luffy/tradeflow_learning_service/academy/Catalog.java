package com.luffy.tradeflow_learning_service.academy;

import java.util.List;
import java.util.Map;

/** Academy content, loaded read-only from versioned files (see ContentCatalog). */
public final class Catalog {

    private Catalog() {
    }

    public record Option(String key, String text, boolean correct) {
    }

    public record Question(String key, String prompt, boolean multi, List<Option> options, String explanation,
                           String hash) {

        public java.util.Set<String> correctKeys() {
            return options.stream().filter(Option::correct).map(Option::key)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
    }

    public record Lesson(String slug, String title, String body, String hash) {
    }

    public record QuizSpec(int questionsServed, int timeLimitMinutes) {
    }

    public record Module(String slug, String title, String summary, int position, boolean mandatory,
                         List<Lesson> lessons, QuizSpec quiz, List<Question> questions) {
    }

    public record Exercise(String slug, String title, String module, int position, String description, String rule) {
    }

    /** All content of one version. {@code finalQuestions} is the final readiness assessment bank. */
    public record Content(int version, String hash, List<Module> modules, QuizSpec finalAssessment,
                          List<Question> finalQuestions, List<Exercise> exercises, Map<String, Module> bySlug) {
    }
}
