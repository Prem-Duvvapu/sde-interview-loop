package com.premd.interviewloop.progress;

import com.premd.interviewloop.domain.ReadinessSnapshot;
import com.premd.interviewloop.domain.repository.ReadinessSnapshotRepository;
import com.premd.interviewloop.llm.AppSettingsStore;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Exposes readiness and trend endpoints.
 */
@RestController
@RequestMapping("/api/progress")
public class ProgressController {

    private final ReadinessCalculator readinessCalculator;
    private final ReadinessSnapshotRepository snapshotRepo;
    private final AppSettingsStore settingsStore;

    public ProgressController(ReadinessCalculator readinessCalculator,
                              ReadinessSnapshotRepository snapshotRepo,
                              AppSettingsStore settingsStore) {
        this.readinessCalculator = readinessCalculator;
        this.snapshotRepo = snapshotRepo;
        this.settingsStore = settingsStore;
    }

    /**
     * Current readiness for a company profile: band, per-module scores, failing minimums,
     * confidence level.
     */
    @GetMapping("/readiness/{companyProfileId}")
    public ResponseEntity<?> getReadiness(@PathVariable String companyProfileId) {
        int currentEpoch = settingsStore.comparabilityEpoch();
        ReadinessCalculator.ReadinessResult result = readinessCalculator
                .computeReadiness(companyProfileId, currentEpoch);
        if (result.error() != null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", result.error()));
        }
        return ResponseEntity.ok(result);
    }

    /**
     * Historical score progression for a module within a company, annotated with the
     * comparability epoch. Scores from different epochs are not comparable — the UI must
     * be able to mark the break.
     */
    @GetMapping("/trend")
    public ResponseEntity<?> getTrend(@RequestParam String module,
                                      @RequestParam String company) {
        List<ReadinessSnapshot> snapshots = snapshotRepo
                .findByCompanyProfileIdAndModuleTypeOrderByTakenAtDesc(company, module);

        if (snapshots.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "No snapshots for module=" + module + " company=" + company));
        }

        List<Map<String, Object>> points = snapshots.stream()
                .map(s -> {
                    Map<String, Object> point = new LinkedHashMap<>();
                    point.put("takenAt", s.getTakenAt().toString());
                    point.put("score", s.getScore());
                    point.put("sampleSize", s.getSampleSize());
                    point.put("comparabilityEpoch", s.getComparabilityEpoch());
                    return point;
                })
                .toList();

        return ResponseEntity.ok(Map.of(
                "module", module,
                "company", company,
                "comparabilityEpoch", settingsStore.comparabilityEpoch(),
                "points", points
        ));
    }
}
