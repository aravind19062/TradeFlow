package com.luffy.tradeflow_learning_service.academy;

import com.luffy.tradeflow_learning_service.config.AcademyProperties;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads the Academy content once at startup from versioned files in Git and validates it. A
 * malformed catalog stops the service rather than serving a broken quiz. Every question and lesson
 * gets a SHA-256 hash, which quiz attempts record so later wording changes cannot silently alter
 * the meaning of a past result.
 */
@Component
public class ContentCatalog {

    private final Catalog.Content content;

    public ContentCatalog(AcademyProperties properties, JsonMapper json) {
        this.content = load(properties.catalog(), json);
    }

    public Catalog.Content content() {
        return content;
    }

    public Catalog.Module module(String slug) {
        Catalog.Module m = content.bySlug().get(slug);
        if (m == null) {
            throw com.luffy.tradeflow.common.web.ApiException.notFound("Module " + slug);
        }
        return m;
    }

    static Catalog.Content load(String base, JsonMapper json) {
        ResourceLoader loader = new DefaultResourceLoader();
        String root = base.endsWith("/") ? base : base + "/";
        JsonNode catalog = readJson(loader, root + "catalog.json", json);
        int version = catalog.get("contentVersion").asInt();
        List<Catalog.Module> modules = new ArrayList<>();
        Map<String, Catalog.Module> bySlug = new LinkedHashMap<>();
        StringBuilder allHashes = new StringBuilder();
        for (JsonNode m : catalog.get("modules")) {
            String slug = m.get("slug").asString();
            List<Catalog.Lesson> lessons = new ArrayList<>();
            for (JsonNode l : m.get("lessons")) {
                String lessonSlug = l.get("slug").asString();
                String body = readText(loader, root + "lessons/" + slug + "/" + lessonSlug + ".md");
                lessons.add(new Catalog.Lesson(lessonSlug, l.get("title").asString(), body, sha256(body)));
            }
            List<Catalog.Question> questions = questions(loader, root + "questions/" + slug + ".json", json);
            JsonNode quiz = m.get("quiz");
            Catalog.QuizSpec spec = new Catalog.QuizSpec(quiz.get("questionsServed").asInt(),
                    quiz.get("timeLimitMinutes").asInt());
            require(!lessons.isEmpty(), "module " + slug + " has no lessons");
            require(spec.questionsServed() <= questions.size(), "module " + slug + " serves more questions than it has");
            Catalog.Module module = new Catalog.Module(slug, m.get("title").asString(), m.get("summary").asString(),
                    m.get("position").asInt(), m.get("mandatory").asBoolean(), List.copyOf(lessons), spec,
                    List.copyOf(questions));
            require(bySlug.put(slug, module) == null, "duplicate module " + slug);
            modules.add(module);
            lessons.forEach(l -> allHashes.append(l.hash()));
            questions.forEach(q -> allHashes.append(q.hash()));
        }
        JsonNode fin = catalog.get("finalAssessment");
        Catalog.QuizSpec finalSpec = new Catalog.QuizSpec(fin.get("questionsServed").asInt(),
                fin.get("timeLimitMinutes").asInt());
        List<Catalog.Question> finalQuestions = questions(loader, root + "questions/final.json", json);
        require(finalSpec.questionsServed() <= finalQuestions.size(), "final assessment serves too many questions");
        finalQuestions.forEach(q -> allHashes.append(q.hash()));
        List<Catalog.Exercise> exercises = new ArrayList<>();
        for (JsonNode e : catalog.get("exercises")) {
            String module = e.get("module").asString();
            require(bySlug.containsKey(module), "exercise refers to unknown module " + module);
            exercises.add(new Catalog.Exercise(e.get("slug").asString(), e.get("title").asString(), module,
                    e.get("position").asInt(), e.get("description").asString(), e.get("rule").asString()));
        }
        return new Catalog.Content(version, sha256(allHashes.toString()), List.copyOf(modules), finalSpec,
                List.copyOf(finalQuestions), List.copyOf(exercises), Map.copyOf(bySlug));
    }

    private static List<Catalog.Question> questions(ResourceLoader loader, String location, JsonMapper json) {
        List<Catalog.Question> out = new ArrayList<>();
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (JsonNode q : readJson(loader, location, json)) {
            String key = q.get("key").asString();
            require(keys.add(key), "duplicate question " + key);
            List<Catalog.Option> options = new ArrayList<>();
            for (JsonNode o : q.get("options")) {
                options.add(new Catalog.Option(o.get("key").asString(), o.get("text").asString(),
                        o.get("correct").asBoolean()));
            }
            long correct = options.stream().filter(Catalog.Option::correct).count();
            boolean multi = q.get("multi").asBoolean();
            require(correct >= 1, "question " + key + " has no correct option");
            require(multi || correct == 1, "single-answer question " + key + " has several correct options");
            out.add(new Catalog.Question(key, q.get("prompt").asString(), multi, List.copyOf(options),
                    q.get("explanation").asString(), sha256(q.toString())));
        }
        return out;
    }

    private static JsonNode readJson(ResourceLoader loader, String location, JsonMapper json) {
        return json.readTree(readText(loader, location));
    }

    private static String readText(ResourceLoader loader, String location) {
        Resource r = loader.getResource(location);
        try (InputStream in = r.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Academy content missing: " + location, e);
        }
    }

    private static void require(boolean ok, String message) {
        if (!ok) {
            throw new IllegalStateException("Invalid Academy content: " + message);
        }
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
