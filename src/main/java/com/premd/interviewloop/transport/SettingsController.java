package com.premd.interviewloop.transport;

import com.premd.interviewloop.llm.AppSettingsStore;
import com.premd.interviewloop.llm.ProviderCatalog;
import com.premd.interviewloop.llm.ProviderInfo;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * The interviewer and evaluator provider/model bindings. The interviewer binding is a
 * free override — any configured provider meeting the interviewer floor may take it. The
 * evaluator binding requires {@code confirmEpochChange: true} because changing it starts a
 * new comparability epoch (PROJECT_PLAN.md §1.5): scores before and after are no longer on
 * the same scale, and the readiness dashboard needs to know where that break is.
 */
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final AppSettingsStore settingsStore;
    private final ProviderCatalog catalog;

    public SettingsController(AppSettingsStore settingsStore, ProviderCatalog catalog) {
        this.settingsStore = settingsStore;
        this.catalog = catalog;
    }

    public record RoleBindingDto(String provider, String model) {}

    public record EvaluatorBindingDto(String provider, String model, Integer comparabilityEpoch) {}

    public record AppSettingsDto(RoleBindingDto interviewer, EvaluatorBindingDto evaluator) {}

    public record PutBindingBody(String provider, String model, Boolean confirmEpochChange) {}

    @GetMapping
    public AppSettingsDto getSettings() {
        return snapshot();
    }

    @PutMapping("/interviewer")
    public ResponseEntity<?> putInterviewer(@RequestBody PutBindingBody body) {
        ResponseEntity<?> invalid = validateBinding(body, "interviewer");
        if (invalid != null) {
            return invalid;
        }
        settingsStore.setInterviewer(body.provider(), body.model());
        return ResponseEntity.ok(snapshot());
    }

    @PutMapping("/evaluator")
    public ResponseEntity<?> putEvaluator(@RequestBody PutBindingBody body) {
        ResponseEntity<?> invalid = validateBinding(body, "evaluator");
        if (invalid != null) {
            return invalid;
        }
        AppSettingsStore.RoleBinding current = settingsStore.evaluatorBinding();
        boolean changing = !current.provider().equals(body.provider()) || !current.model().equals(body.model());
        if (changing && !Boolean.TRUE.equals(body.confirmEpochChange())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "Changing the evaluator starts a new comparability epoch — "
                            + "readiness scores before and after will not be directly comparable.",
                    "requiresConfirmation", true,
                    "currentEpoch", settingsStore.comparabilityEpoch()));
        }
        settingsStore.setEvaluator(body.provider(), body.model());
        return ResponseEntity.ok(snapshot());
    }

    private ResponseEntity<?> validateBinding(PutBindingBody body, String role) {
        if (body.provider() == null || body.provider().isBlank() || body.model() == null || body.model().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "provider and model are required"));
        }
        if (!catalog.isImplemented(body.provider())) {
            return ResponseEntity.badRequest().body(Map.of("error", "No adapter built for provider '" + body.provider() + "'"));
        }
        ProviderInfo info = catalog.get(body.provider());
        boolean meetsFloor = "evaluator".equals(role) ? info.meetsEvaluatorFloor() : info.meetsInterviewerFloor();
        if (!meetsFloor) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Provider '" + body.provider() + "' does not meet the " + role + " capability floor"));
        }
        return null;
    }

    private AppSettingsDto snapshot() {
        AppSettingsStore.RoleBinding interviewer = settingsStore.interviewerBinding();
        AppSettingsStore.RoleBinding evaluator = settingsStore.evaluatorBinding();
        return new AppSettingsDto(
                new RoleBindingDto(interviewer.provider(), interviewer.model()),
                new EvaluatorBindingDto(evaluator.provider(), evaluator.model(), settingsStore.comparabilityEpoch()));
    }
}
