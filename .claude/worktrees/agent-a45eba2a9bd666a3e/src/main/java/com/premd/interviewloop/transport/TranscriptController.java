package com.premd.interviewloop.transport;

import com.premd.interviewloop.domain.ArtifactSnapshot;
import com.premd.interviewloop.domain.TranscriptTurn;
import com.premd.interviewloop.transcript.TranscriptService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/rounds")
public class TranscriptController {

    private final TranscriptService transcriptService;

    public TranscriptController(TranscriptService transcriptService) {
        this.transcriptService = transcriptService;
    }

    @GetMapping("/{roundId}/transcript")
    public List<TranscriptTurn> getTranscript(@PathVariable Long roundId) {
        return transcriptService.getTranscript(roundId);
    }

    @GetMapping("/{roundId}/artifacts")
    public List<ArtifactSnapshot> getArtifacts(@PathVariable Long roundId) {
        return transcriptService.getArtifacts(roundId);
    }
}
