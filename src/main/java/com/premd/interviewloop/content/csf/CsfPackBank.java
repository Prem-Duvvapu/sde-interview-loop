package com.premd.interviewloop.content.csf;

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
import java.util.stream.Collectors;

/**
 * Loads CS-fundamentals round packs from {@code question-bank/cs_fundamentals/*.yaml} at
 * startup. Same fail-fast, random-among-matching-difficulty pattern as the other banks.
 *
 * <p>Tag matching here is broader than the other banks: a pack's {@code tags} describe the
 * topic areas it covers, so {@link #selectFor(String, List)} treats the round's focus tags
 * as a preference signal (prefer packs covering more of them) rather than a hard filter —
 * with only a handful of packs, hard-filtering would routinely leave nothing to pick.
 */
@Component
public class CsfPackBank {

    private static final Logger log = LoggerFactory.getLogger(CsfPackBank.class);

    private static final List<String> DIFFICULTY_ORDER = List.of("easy", "medium", "medium-hard", "hard");

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    @Value("${app.question-bank-dir:question-bank}")
    private String bankDir;

    private final Map<String, CsfPack> bySlug = new LinkedHashMap<>();
    private final Map<String, String> contentHashes = new HashMap<>();

    @PostConstruct
    public void init() {
        Path dir = Paths.get(bankDir, "cs_fundamentals");
        if (!Files.isDirectory(dir)) {
            throw new IllegalStateException(
                    "CS fundamentals question bank directory not found: " + dir.toAbsolutePath());
        }

        List<String> errors = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.yaml")) {
            for (Path file : stream) {
                String filename = file.getFileName().toString();
                String stem = filename.substring(0, filename.length() - 5);
                try {
                    byte[] raw = Files.readAllBytes(file);
                    CsfPack pack = yamlMapper.readValue(raw, CsfPack.class);
                    validate(stem, pack);
                    bySlug.put(pack.getSlug(), pack);
                    contentHashes.put(pack.getSlug(),
                            sha256(pack.getTopics().stream()
                                    .map(t -> t.getName() + "\n" + t.getQuestions().stream()
                                            .map(CsfQuestion::getPrompt)
                                            .collect(Collectors.joining("\n")))
                                    .collect(Collectors.joining("\n"))));
                } catch (Exception e) {
                    errors.add(filename + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to scan question bank directory: " + dir, e);
        }

        if (!errors.isEmpty()) {
            StringBuilder sb = new StringBuilder("CS fundamentals question bank validation failed:\n");
            errors.forEach(err -> sb.append("  - ").append(err).append("\n"));
            throw new IllegalStateException(sb.toString());
        }
        if (bySlug.isEmpty()) {
            throw new IllegalStateException("No CS fundamentals packs found in " + dir.toAbsolutePath());
        }

        log.info("Loaded {} CS fundamentals pack(s): {}", bySlug.size(), bySlug.keySet());
    }

    private void validate(String stem, CsfPack pack) {
        if (pack.getSlug() == null || pack.getSlug().isBlank()) {
            throw new IllegalArgumentException("missing slug");
        }
        if (!stem.equals(pack.getSlug())) {
            throw new IllegalArgumentException("slug '" + pack.getSlug()
                    + "' does not match filename stem '" + stem + "'");
        }
        if (pack.getTitle() == null || pack.getTitle().isBlank()) {
            throw new IllegalArgumentException("missing title");
        }
        if (!DIFFICULTY_ORDER.contains(pack.getDifficulty())) {
            throw new IllegalArgumentException("difficulty must be one of " + DIFFICULTY_ORDER
                    + ", got '" + pack.getDifficulty() + "'");
        }
        if (pack.getTopics() == null || pack.getTopics().isEmpty()) {
            throw new IllegalArgumentException("a pack must contain at least one topic");
        }
        for (CsfTopic topic : pack.getTopics()) {
            if (topic.getName() == null || topic.getName().isBlank()) {
                throw new IllegalArgumentException("topic missing name");
            }
            if (topic.getQuestions() == null || topic.getQuestions().isEmpty()) {
                throw new IllegalArgumentException("topic '" + topic.getName() + "' has no questions");
            }
            for (CsfQuestion q : topic.getQuestions()) {
                if (q.getPrompt() == null || q.getPrompt().isBlank()) {
                    throw new IllegalArgumentException("topic '" + topic.getName() + "' has a question with no prompt");
                }
            }
        }
    }

    public CsfPack get(String slug) {
        CsfPack p = bySlug.get(slug);
        if (p == null) {
            throw new NoSuchElementException("No CS fundamentals pack with slug: " + slug);
        }
        return p;
    }

    public String contentHash(String slug) {
        String hash = contentHashes.get(slug);
        if (hash == null) {
            throw new NoSuchElementException("No CS fundamentals pack with slug: " + slug);
        }
        return hash;
    }

    /**
     * Pick a random pack at the requested difficulty; among candidates, prefer the ones that
     * cover more of the round's focus tags. Falls back to the nearest difficulty when the
     * target has no packs, mirroring the other banks.
     */
    public CsfPack selectFor(String difficultyTarget, List<String> focusTags) {
        String target = difficultyTarget == null || difficultyTarget.isBlank() ? "medium" : difficultyTarget;
        Set<String> wanted = focusTags == null ? Set.of()
                : focusTags.stream().map(t -> t.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());

        for (int distance = 0; distance < DIFFICULTY_ORDER.size(); distance++) {
            int targetIndex = Math.max(0, DIFFICULTY_ORDER.indexOf(target));
            List<Integer> order = new ArrayList<>();
            for (int i = 0; i < DIFFICULTY_ORDER.size(); i++) order.add(i);
            order.sort(Comparator.comparingInt(i -> Math.abs(i - targetIndex)));

            Integer chosenIndex = distance < order.size() ? order.get(distance) : null;
            if (chosenIndex == null) break;
            String candidateDifficulty = DIFFICULTY_ORDER.get(chosenIndex);

            List<CsfPack> matches = bySlug.values().stream()
                    .filter(p -> p.getDifficulty().equals(candidateDifficulty))
                    .toList();
            if (matches.isEmpty()) continue;

            if (distance > 0) {
                log.warn("No CS fundamentals pack at difficulty '{}', falling back to '{}'",
                        target, candidateDifficulty);
            }
            return pickPreferringFocus(matches, wanted);
        }
        throw new IllegalStateException("CS fundamentals question bank is empty");
    }

    /** Random among the best focus coverage; all-tied or no-focus falls back to uniform random. */
    private CsfPack pickPreferringFocus(List<CsfPack> matches, Set<String> wanted) {
        if (wanted.isEmpty()) {
            return matches.get(ThreadLocalRandom.current().nextInt(matches.size()));
        }
        int best = -1;
        List<CsfPack> bestPacks = new ArrayList<>();
        for (CsfPack p : matches) {
            long covered = p.getTags() == null ? 0 : p.getTags().stream()
                    .filter(t -> wanted.contains(t.toLowerCase(Locale.ROOT)))
                    .count();
            if (covered > best) {
                best = (int) covered;
                bestPacks.clear();
            }
            if (covered == best) {
                bestPacks.add(p);
            }
        }
        return bestPacks.get(ThreadLocalRandom.current().nextInt(bestPacks.size()));
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
