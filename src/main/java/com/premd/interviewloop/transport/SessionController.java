package com.premd.interviewloop.transport;

import com.premd.interviewloop.domain.InterviewSession;
import com.premd.interviewloop.domain.SessionRound;
import com.premd.interviewloop.domain.enums.ModuleType;
import com.premd.interviewloop.domain.enums.SessionMode;
import com.premd.interviewloop.session.SessionManager;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private final SessionManager sessionManager;

    public SessionController(SessionManager sessionManager) {
        this.sessionManager = sessionManager;
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
        return sessionManager.completeRound(roundId);
    }

    @DeleteMapping("/{id}")
    public InterviewSession abandonSession(@PathVariable Long id) {
        return sessionManager.abandonSession(id);
    }
}
