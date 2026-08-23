package com.premd.interviewloop.transport;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.premd.interviewloop.domain.RoundEvaluation;
import com.premd.interviewloop.domain.repository.RoundEvaluationRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/rounds")
public class EvaluationController {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final RoundEvaluationRepository evaluationRepo;

    public EvaluationController(RoundEvaluationRepository evaluationRepo) {
        this.evaluationRepo = evaluationRepo;
    }

    public record EvaluationDto(
            String rubricVersion,
            String evaluatorProvider,
            String evaluatorModel,
            int comparabilityEpoch,
            Map<String, Integer> scores,
            List<String> strengths,
            List<String> gaps,
            String readinessBand,
            String narrativeMd
    ) {}

    @GetMapping("/{roundId}/evaluation")
    public ResponseEntity<?> getEvaluation(@PathVariable Long roundId) {
        return evaluationRepo.findByRoundId(roundId)
                .<ResponseEntity<?>>map(e -> ResponseEntity.ok(toDto(e)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "No evaluation yet for round " + roundId)));
    }

    private EvaluationDto toDto(RoundEvaluation e) {
        return new EvaluationDto(
                e.getRubricVersion(),
                e.getEvaluatorProvider(),
                e.getEvaluatorModel(),
                e.getComparabilityEpoch(),
                readJson(e.getScores(), new TypeReference<Map<String, Integer>>() {}, Map.of()),
                readJson(e.getStrengths(), new TypeReference<List<String>>() {}, List.of()),
                readJson(e.getGaps(), new TypeReference<List<String>>() {}, List.of()),
                e.getReadinessBand(),
                e.getNarrativeMd());
    }

    private <T> T readJson(String raw, TypeReference<T> type, T fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return JSON.readValue(raw, type);
        } catch (Exception ex) {
            return fallback;
        }
    }
}
