package com.premd.interviewloop.content.java;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Loads Java deep-dive scenarios from {@code question-bank/java_deep_dive/*.yaml} at
 * startup. Same fail-fast, random-among-matching-difficulty pattern as the other banks.
 */
@Component
public class JavaScenarioBank {

    private static final Logger log = LoggerFactory.getLogger(JavaScenarioBank.class);

    private static final List<String> DIFFICULTY_ORDER = List.of("easy", "medium", "medium-hard", "hard");

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    @Value("${app.question-bank-dir:question-bank}")
    private String bankDir;

    private final Map<String, JavaScenario> bySlug = new LinkedHashMap<>();
    private final Map<String, String> contentHashes = new HashMap<>();

    @PostConstruct
    public void init() {
        Path dir = Paths.get(bankDir, "java_deep_dive");
        if (!Files.isDirectory(dir)) {
            throw new IllegalStateException(
                    "Java deep-dive question bank directory not found: " + dir.toAbsolutePath());
        }

        List<String> errors = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.yaml")) {
            for (Path file : stream) {
                String filename = file.getFileName().toString();
                String stem = filename.substring(0, filename.length() - 5);
                try {
                    byte[] raw = Files.readAllBytes(file);
                    JavaScenario s = yamlMapper.readValue(raw, JavaScenario.class);
                    validate(stem, s);
                    bySlug.put(s.getSlug(), s);
                    contentHashes.put(s.getSlug(), sha256(s.getScenarioText()));
                } catch (Exception e) {
                    errors.add(filename + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to scan question bank directory: " + dir, e);
        }

        if (!errors.isEmpty()) {
            StringBuilder sb = new StringBuilder("Java deep-dive question bank validation failed:\n");
            errors.forEach(err -> sb.append("  - ").append(err).append("\n"));
            throw new IllegalStateException(sb.toString());
        }
        if (bySlug.isEmpty()) {
            throw new IllegalStateException("No Java deep-dive scenarios found in " + dir.toAbsolutePath());
        }

        log.info("Loaded {} Java deep-dive scenario(s): {}", bySlug.size(), bySlug.keySet());
    }

    private void validate(String stem, JavaScenario s) {
        if (s.getSlug() == null || s.getSlug().isBlank()) {
            throw new IllegalArgumentException("missing slug");
        }
        if (!stem.equals(s.getSlug())) {
            throw new IllegalArgumentException("slug '" + s.getSlug()
                    + "' does not match filename stem '" + stem + "'");
        }
        if (s.getTitle() == null || s.getTitle().isBlank()) {
            throw new IllegalArgumentException("missing title");
        }
        if (!DIFFICULTY_ORDER.contains(s.getDifficulty())) {
            throw new IllegalArgumentException("difficulty must be one of " + DIFFICULTY_ORDER
                    + ", got '" + s.getDifficulty() + "'");
        }
        if (s.getScenarioText() == null || s.getScenarioText().isBlank()) {
            throw new IllegalArgumentException("missing scenario");
        }
        if (s.getExpectedDiagnosis() == null || s.getExpectedDiagnosis().isBlank()) {
            throw new IllegalArgumentException("missing expected_diagnosis");
        }
        if (s.getInterviewerNotes() == null || s.getInterviewerNotes().isBlank()) {
            throw new IllegalArgumentException("missing interviewer_notes");
        }
    }

    public JavaScenario get(String slug) {
        JavaScenario s = bySlug.get(slug);
        if (s == null) {
            throw new NoSuchElementException("No Java deep-dive scenario with slug: " + slug);
        }
        return s;
    }

    public String contentHash(String slug) {
        String hash = contentHashes.get(slug);
        if (hash == null) {
            throw new NoSuchElementException("No Java deep-dive scenario with slug: " + slug);
        }
        return hash;
    }

    /**
     * Pick a random scenario at the requested difficulty, or the nearest difficulty with any
     * scenarios if there's no exact match — mirrors the other banks.
     */
    public JavaScenario selectFor(String difficultyTarget) {
        String target = difficultyTarget == null || difficultyTarget.isBlank() ? "medium" : difficultyTarget;
        List<JavaScenario> exact = bySlug.values().stream()
                .filter(s -> s.getDifficulty().equals(target))
                .toList();
        if (!exact.isEmpty()) {
            return exact.get(ThreadLocalRandom.current().nextInt(exact.size()));
        }

        int targetIndex = DIFFICULTY_ORDER.indexOf(target);
        int startIndex = targetIndex < 0 ? 1 : targetIndex;
        List<Integer> byDistance = new ArrayList<>();
        for (int i = 0; i < DIFFICULTY_ORDER.size(); i++) {
            byDistance.add(i);
        }
        byDistance.sort(Comparator.comparingInt(i -> Math.abs(i - startIndex)));

        for (int i : byDistance) {
            String candidate = DIFFICULTY_ORDER.get(i);
            List<JavaScenario> matches = bySlug.values().stream()
                    .filter(s -> s.getDifficulty().equals(candidate))
                    .toList();
            if (!matches.isEmpty()) {
                log.warn("No Java deep-dive scenario at difficulty '{}', falling back to '{}'",
                        target, candidate);
                return matches.get(ThreadLocalRandom.current().nextInt(matches.size()));
            }
        }
        throw new IllegalStateException("Java deep-dive question bank is empty");
    }

    private String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
