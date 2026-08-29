package com.premd.interviewloop.transport;

import com.premd.interviewloop.domain.InterviewSession;
import com.premd.interviewloop.domain.RoundEvaluation;
import com.premd.interviewloop.domain.SessionRound;
import com.premd.interviewloop.domain.enums.ModuleType;
import com.premd.interviewloop.domain.enums.SessionMode;
import com.premd.interviewloop.evaluation.RoundEvaluator;
import com.premd.interviewloop.session.SessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private static final Logger log = LoggerFactory.getLogger(SessionController.class);

    private final SessionManager sessionManager;
    private final RoundEvaluator roundEvaluator;

    public SessionController(SessionManager sessionManager, RoundEvaluator roundEvaluator) {
        this.sessionManager = sessionManager;
        this.roundEvaluator = roundEvaluator;
    }

    @PostMapping
    public ResponseEntity<InterviewSession> createSession(@RequestBody Map<String, String> body) {
        String companyProfileId = body.get("companyProfileId");
        String modeStr = body.getOrDefault("mode", "single_module");
        String providerId = body.getOrDefault("providerId", "google");
        String modelId = body.get("modelId");

        SessionMode mode = "full_loop".equals(modeStr) ? SessionMode.FULL_LOOP : SessionMode.SINGLE_MODULE;

        InterviewSession session;
        if (mode == SessionMode.SINGLE_MODULE) {
            String moduleStr = body.getOrDefault("moduleType", "dsa");
            ModuleType moduleType = ModuleType.fromValue(moduleStr);
            String difficulty = body.getOrDefault("difficultyTarget", "medium");
            session = sessionManager.createSingleModuleSession(
                    companyProfileId, moduleType, difficulty, providerId, modelId);
        } else {
            session = sessionManager.createSession(companyProfileId, mode, providerId, modelId);
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(session);
    }

    @GetMapping
    public List<InterviewSession> listSessions() {
        return sessionManager.listSessions();
    }

    @GetMapping("/{id}")
    public InterviewSession getSession(@PathVariable Long id) {
        return sessionManager.getSession(id);
    }

    @PostMapping("/{sessionId}/rounds/{roundId}/start")
    public SessionRound startRound(@PathVariable Long sessionId, @PathVariable Long roundId) {
        return sessionManager.startRound(roundId);
    }

    @PostMapping("/{sessionId}/rounds/{roundId}/complete")
    public SessionRound completeRound(@PathVariable Long sessionId, @PathVariable Long roundId) {
        SessionRound round = sessionManager.completeRound(roundId);
        // Evaluation is best-effort and must never undo a completion that already happened —
        // the round stays COMPLETED even if the evaluator fails or times out.
        try {
            RoundEvaluation evaluation = roundEvaluator.evaluate(roundId);
            sessionManager.prepareNextRound(roundId, evaluation);
        } catch (Exception e) {
            log.warn("Evaluation failed for round {}: {}", roundId, e.getMessage());
            try {
                sessionManager.prepareNextRound(roundId, null);
            } catch (Exception advanceError) {
                log.warn("Could not prepare next full-loop round after {}: {}", roundId, advanceError.getMessage());
            }
        }
        return round;
    }

    @DeleteMapping("/{id}")
    public InterviewSession abandonSession(@PathVariable Long id) {
        return sessionManager.abandonSession(id);
    }
}
