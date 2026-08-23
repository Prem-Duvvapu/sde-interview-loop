package com.premd.interviewloop.content.dsa;

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
 * Loads DSA questions from {@code question-bank/dsa/*.yaml} at startup. Fail-fast, same
 * pattern as {@link com.premd.interviewloop.profile.ProfileLoader} — a malformed question
 * file should stop the app at boot, not surface as a broken round mid-interview.
 *
 * <p>Selection is random-among-matching-difficulty. With a bank this small, tracking which
 * questions a candidate has recently seen would be more machinery than it's worth — revisit
 * once the bank is large enough that repeats are the more likely outcome.
 */
@Component
public class DsaQuestionBank {

    private static final Logger log = LoggerFactory.getLogger(DsaQuestionBank.class);

    /** Difficulty ordering used to find the nearest match when a round asks for one with no question. */
    private static final List<String> DIFFICULTY_ORDER = List.of("easy", "medium", "medium-hard", "hard");

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    @Value("${app.question-bank-dir:question-bank}")
    private String bankDir;

    private final Map<String, DsaQuestion> bySlug = new LinkedHashMap<>();
    private final Map<String, String> contentHashes = new HashMap<>();

    @PostConstruct
    public void init() {
        Path dir = Paths.get(bankDir, "dsa");
        if (!Files.isDirectory(dir)) {
            throw new IllegalStateException("DSA question bank directory not found: " + dir.toAbsolutePath());
        }

        List<String> errors = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.yaml")) {
            for (Path file : stream) {
                String filename = file.getFileName().toString();
                String stem = filename.substring(0, filename.length() - 5);
                try {
                    byte[] raw = Files.readAllBytes(file);
                    DsaQuestion q = yamlMapper.readValue(raw, DsaQuestion.class);
                    validate(filename, stem, q);
                    bySlug.put(q.getSlug(), q);
                    contentHashes.put(q.getSlug(), sha256(q.getStatement()));
                } catch (Exception e) {
                    errors.add(filename + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to scan question bank directory: " + dir, e);
        }

        if (!errors.isEmpty()) {
            StringBuilder sb = new StringBuilder("DSA question bank validation failed:\n");
            errors.forEach(err -> sb.append("  - ").append(err).append("\n"));
            throw new IllegalStateException(sb.toString());
        }
        if (bySlug.isEmpty()) {
            throw new IllegalStateException("No DSA questions found in " + dir.toAbsolutePath());
        }

        log.info("Loaded {} DSA question(s): {}", bySlug.size(), bySlug.keySet());
    }

    private void validate(String filename, String stem, DsaQuestion q) {
        if (q.getSlug() == null || q.getSlug().isBlank()) {
            throw new IllegalArgumentException("missing slug");
        }
        if (!stem.equals(q.getSlug())) {
            throw new IllegalArgumentException("slug '" + q.getSlug() + "' does not match filename stem '" + stem + "'");
        }
        if (q.getTitle() == null || q.getTitle().isBlank()) {
            throw new IllegalArgumentException("missing title");
        }
        if (!DIFFICULTY_ORDER.contains(q.getDifficulty())) {
            throw new IllegalArgumentException("difficulty must be one of " + DIFFICULTY_ORDER + ", got '" + q.getDifficulty() + "'");
        }
        if (q.getStatement() == null || q.getStatement().isBlank()) {
            throw new IllegalArgumentException("missing statement");
        }
        if (q.getInterviewerNotes() == null || q.getInterviewerNotes().isBlank()) {
            throw new IllegalArgumentException("missing interviewer_notes");
        }
    }

    public DsaQuestion get(String slug) {
        DsaQuestion q = bySlug.get(slug);
        if (q == null) {
            throw new NoSuchElementException("No DSA question with slug: " + slug);
        }
        return q;
    }

    public String contentHash(String slug) {
        String hash = contentHashes.get(slug);
        if (hash == null) {
            throw new NoSuchElementException("No DSA question with slug: " + slug);
        }
        return hash;
    }

    /**
     * Pick a random question at the requested difficulty, or the nearest difficulty with any
     * questions if there's no exact match.
     */
    public DsaQuestion selectFor(String difficultyTarget) {
        String target = difficultyTarget == null || difficultyTarget.isBlank() ? "medium" : difficultyTarget;
        List<DsaQuestion> exact = bySlug.values().stream()
                .filter(q -> q.getDifficulty().equals(target))
                .toList();
        if (!exact.isEmpty()) {
            return exact.get(ThreadLocalRandom.current().nextInt(exact.size()));
        }

        int targetIndex = DIFFICULTY_ORDER.indexOf(target);
        int startIndex = targetIndex < 0 ? 1 : targetIndex; // unknown difficulty: start from "medium"
        List<Integer> byDistance = new ArrayList<>();
        for (int i = 0; i < DIFFICULTY_ORDER.size(); i++) {
            byDistance.add(i);
        }
        byDistance.sort(Comparator.comparingInt(i -> Math.abs(i - startIndex)));

        for (int i : byDistance) {
            String candidate = DIFFICULTY_ORDER.get(i);
            List<DsaQuestion> matches = bySlug.values().stream()
                    .filter(q -> q.getDifficulty().equals(candidate))
                    .toList();
            if (!matches.isEmpty()) {
                log.warn("No DSA question at difficulty '{}', falling back to '{}'", target, candidate);
                return matches.get(ThreadLocalRandom.current().nextInt(matches.size()));
            }
        }
        throw new IllegalStateException("DSA question bank is empty");
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
