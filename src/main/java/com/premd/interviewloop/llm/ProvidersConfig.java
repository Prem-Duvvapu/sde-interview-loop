package com.premd.interviewloop.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Loads config/providers.yaml into typed objects at startup.
 * Provider model IDs and pricing live here, not in code.
 */
@Component
public class ProvidersConfig {

    private static final Logger log = LoggerFactory.getLogger(ProvidersConfig.class);

    @Value("${app.providers-config:config/providers.yaml}")
    private String configPath;

    private ProvidersYaml config;

    @PostConstruct
    public void init() {
        try {
            ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
            byte[] raw = Files.readAllBytes(Paths.get(configPath));
            config = yamlMapper.readValue(raw, ProvidersYaml.class);
            log.info("Loaded provider config from {}", configPath);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load providers config: " + configPath, e);
        }
    }

    public Defaults getDefaults() { return config.defaults; }

    public List<ProviderEntry> getProviders() { return config.providers; }

    public Optional<ProviderEntry> getProviderEntry(String id) {
        return config.providers.stream()
                .filter(p -> id.equals(p.id))
                .findFirst();
    }

    // -- YAML mapping POJOs --

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ProvidersYaml {
        public Defaults defaults;
        public List<ProviderEntry> providers;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Defaults {
        public EvaluatorDefault evaluator;
        public InterviewerDefault interviewer;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EvaluatorDefault {
        public String provider;
        public String model;
        @JsonProperty("comparability_epoch")
        public int comparabilityEpoch;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class InterviewerDefault {
        public String provider;
        public String model;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ProviderEntry {
        public String id;
        @JsonProperty("display_name")
        public String displayName;
        public boolean enabled;
        @JsonProperty("api_key_env")
        public String apiKeyEnv;
        public String sdk;
        public List<ModelEntry> models;
        public Map<String, Object> capabilities;
        @JsonProperty("pricing_usd_per_mtok")
        public PricingEntry pricing;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ModelEntry {
        public String id;
        @JsonProperty("role_hint")
        public String roleHint;
        public String thinking;
        @JsonProperty("context_tokens")
        public Integer contextTokens;
        public String notes;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PricingEntry {
        public Object input;  // May be a number or "<set-me>"
        public Object output;

        public double getInputPrice() {
            if (input instanceof Number n) return n.doubleValue();
            return 0.0;
        }

        public double getOutputPrice() {
            if (output instanceof Number n) return n.doubleValue();
            return 0.0;
        }
    }
}
