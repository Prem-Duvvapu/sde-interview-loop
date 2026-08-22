package com.premd.interviewloop.profile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads and validates company profiles from YAML files at startup.
 * Fail-fast: the application refuses to start if any profile is invalid.
 *
 * Supports hot-reload via {@link #reload()}.
 */
@Component
public class ProfileLoader {

    private static final Logger log = LoggerFactory.getLogger(ProfileLoader.class);

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
    private final ObjectMapper jsonMapper = new ObjectMapper();

    @Value("${app.profiles-dir:company-profiles}")
    private String profilesDir;

    /** Profiles keyed by their id (filename stem). Thread-safe for hot-reload. */
    private final Map<String, CompanyProfile> profiles = new ConcurrentHashMap<>();

    /** Content hashes keyed by profile id, for change tracking. */
    private final Map<String, String> contentHashes = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        reload();
        log.info("Loaded {} company profile(s): {}", profiles.size(), profiles.keySet());
    }

    /**
     * (Re)load all profiles from disk. Fail-fast on any validation error.
     */
    public void reload() {
        Path dir = Paths.get(profilesDir);
        if (!Files.isDirectory(dir)) {
            throw new IllegalStateException("Profiles directory not found: " + dir.toAbsolutePath());
        }

        // Load JSON Schema
        JsonSchema schema = loadSchema(dir.resolve("_schema.json"));

        Map<String, CompanyProfile> loaded = new LinkedHashMap<>();
        Map<String, String> hashes = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.yaml")) {
            for (Path file : stream) {
                String filename = file.getFileName().toString();
                String stem = filename.substring(0, filename.length() - 5); // strip .yaml

                try {
                    byte[] raw = Files.readAllBytes(file);
                    String hash = sha256(raw);

                    // Parse YAML to JsonNode for schema validation
                    JsonNode yamlNode = yamlMapper.readTree(raw);

                    // Validate against JSON Schema
                    Set<ValidationMessage> schemaErrors = schema.validate(yamlNode);
                    if (!schemaErrors.isEmpty()) {
                        for (ValidationMessage msg : schemaErrors) {
                            errors.add(filename + ": " + msg.getMessage());
                        }
                        continue;
                    }

                    // Deserialise to POJO
                    CompanyProfile profile = yamlMapper.readValue(raw, CompanyProfile.class);

                    // Invariant 1: id == filename stem
                    if (!stem.equals(profile.getId())) {
                        errors.add(filename + ": id '" + profile.getId() + "' does not match filename stem '" + stem + "'");
                        continue;
                    }

                    // Invariant 2: emphasis weights sum to 1.0 (±0.01)
                    double emphasisSum = profile.getEmphasis().values().stream().mapToDouble(Double::doubleValue).sum();
                    if (Math.abs(emphasisSum - 1.0) > 0.01) {
                        errors.add(filename + ": emphasis weights sum to " + emphasisSum + " (must be 1.0 ±0.01)");
                        continue;
                    }

                    // Invariant 3: round ordinals are 1..n contiguous
                    List<CompanyProfile.Round> rounds = profile.getLoop().getRounds();
                    for (int i = 0; i < rounds.size(); i++) {
                        if (rounds.get(i).getOrdinal() != i + 1) {
                            errors.add(filename + ": round ordinals are not 1..n contiguous (found " +
                                    rounds.get(i).getOrdinal() + " at position " + (i + 1) + ")");
                            break;
                        }
                    }

                    // Invariant 4: total_wall_clock_min == sum of round durations
                    int durationSum = rounds.stream().mapToInt(CompanyProfile.Round::getDurationMin).sum();
                    if (durationSum != profile.getLoop().getTotalWallClockMin()) {
                        errors.add(filename + ": total_wall_clock_min (" + profile.getLoop().getTotalWallClockMin() +
                                ") does not equal sum of round durations (" + durationSum + ")");
                        continue;
                    }

                    loaded.put(stem, profile);
                    hashes.put(stem, hash);
                    log.debug("Loaded profile: {} ({})", stem, profile.getDisplayName());

                } catch (Exception e) {
                    errors.add(filename + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to scan profiles directory: " + dir, e);
        }

        if (!errors.isEmpty()) {
            StringBuilder sb = new StringBuilder("Profile validation failed:\n");
            errors.forEach(err -> sb.append("  - ").append(err).append("\n"));
            throw new IllegalStateException(sb.toString());
        }

        if (loaded.isEmpty()) {
            throw new IllegalStateException("No company profiles found in " + dir.toAbsolutePath());
        }

        profiles.clear();
        profiles.putAll(loaded);
        contentHashes.clear();
        contentHashes.putAll(hashes);
    }

    public CompanyProfile getProfile(String id) {
        CompanyProfile profile = profiles.get(id);
        if (profile == null) {
            throw new NoSuchElementException("No company profile with id: " + id);
        }
        return profile;
    }

    public Collection<CompanyProfile> getAllProfiles() {
        return Collections.unmodifiableCollection(profiles.values());
    }

    public String getContentHash(String profileId) {
        return contentHashes.get(profileId);
    }

    public boolean hasProfile(String id) {
        return profiles.containsKey(id);
    }

    private JsonSchema loadSchema(Path schemaPath) {
        try {
            String schemaContent = Files.readString(schemaPath, StandardCharsets.UTF_8);
            JsonNode schemaNode = jsonMapper.readTree(schemaContent);
            JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
            return factory.getSchema(schemaNode);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load profile schema: " + schemaPath, e);
        }
    }

    private String sha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
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
