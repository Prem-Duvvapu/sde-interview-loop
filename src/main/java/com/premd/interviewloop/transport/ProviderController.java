package com.premd.interviewloop.transport;

import com.premd.interviewloop.llm.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Lets the owner see every known provider, paste or clear its API key, and check that a
 * pasted key actually works — all at runtime, no restart. Keys arrive here in a request
 * body and are handed straight to {@link ProviderKeyStore}; they are never logged, never
 * echoed back, and this class must not let one leak into an exception message either.
 */
@RestController
@RequestMapping("/api/providers")
public class ProviderController {

    private static final Logger log = LoggerFactory.getLogger(ProviderController.class);

    private final ProviderCatalog catalog;
    private final ProviderKeyStore keyStore;
    private final ProviderRegistry providerRegistry;

    public ProviderController(ProviderCatalog catalog, ProviderKeyStore keyStore, ProviderRegistry providerRegistry) {
        this.catalog = catalog;
        this.keyStore = keyStore;
        this.providerRegistry = providerRegistry;
    }

    @GetMapping
    public List<ProviderInfo> listProviders() {
        return catalog.list();
    }

    public record PutKeyBody(String apiKey) {}

    public record KeyMutationResult(String id, boolean configured, KeySource keySource, String maskedKey) {}

    @PutMapping("/{id}/key")
    public ResponseEntity<?> putKey(@PathVariable String id, @RequestBody PutKeyBody body) {
        if (!catalog.isImplemented(id)) {
            return notImplemented(id);
        }
        if (body.apiKey() == null || body.apiKey().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "apiKey must not be blank"));
        }
        keyStore.putUiKey(id, body.apiKey());
        return ResponseEntity.ok(mutationResult(id));
    }

    @DeleteMapping("/{id}/key")
    public ResponseEntity<?> deleteKey(@PathVariable String id) {
        if (!catalog.isImplemented(id)) {
            return notImplemented(id);
        }
        keyStore.clearUiKey(id);
        return ResponseEntity.ok(mutationResult(id));
    }

    public record VerifyResult(boolean ok, Long latencyMs, String model, String message) {}

    @PostMapping("/{id}/verify")
    public ResponseEntity<?> verify(@PathVariable String id) {
        if (!catalog.isImplemented(id)) {
            return notImplemented(id);
        }
        ProviderInfo info = catalog.get(id);
        if (!info.configured()) {
            return ResponseEntity.ok(new VerifyResult(false, null, null, "No API key configured for " + id));
        }
        if (info.models().isEmpty()) {
            return ResponseEntity.ok(new VerifyResult(false, null, null, "No model configured for " + id));
        }
        String model = info.models().get(0).id();
        LlmProvider provider = providerRegistry.requireProvider(id);

        LlmRequest request = new LlmRequest()
                .model(model)
                .maxTokens(16)
                .conversationMessages(List.of(new LlmRequest.Message("user", "Reply with the single word: ok")));

        Instant start = Instant.now();
        try {
            LlmEvent outcome = provider.stream(request)
                    .filter(e -> e.getType() == LlmEvent.Type.ERROR || e.getType() == LlmEvent.Type.DONE)
                    .blockFirst(Duration.ofSeconds(20));
            long latencyMs = Duration.between(start, Instant.now()).toMillis();
            if (outcome != null && outcome.getType() == LlmEvent.Type.ERROR) {
                return ResponseEntity.ok(new VerifyResult(false, latencyMs, model, outcome.getErrorMessage()));
            }
            return ResponseEntity.ok(new VerifyResult(true, latencyMs, model, null));
        } catch (Exception e) {
            long latencyMs = Duration.between(start, Instant.now()).toMillis();
            log.warn("Verification call to provider {} failed: {}", id, e.getClass().getSimpleName());
            return ResponseEntity.ok(new VerifyResult(false, latencyMs, model, "Request failed: " + e.getClass().getSimpleName()));
        }
    }

    private KeyMutationResult mutationResult(String id) {
        ProviderInfo info = catalog.get(id);
        return new KeyMutationResult(id, info.configured(), info.keySource(), info.maskedKey());
    }

    private ResponseEntity<?> notImplemented(String id) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "Provider '" + id + "' has no adapter built yet."));
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<?> handleNotFound(NoSuchElementException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }
}
