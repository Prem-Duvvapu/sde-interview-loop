package com.premd.interviewloop.transcript;

import com.premd.interviewloop.domain.ArtifactSnapshot;
import com.premd.interviewloop.domain.SessionRound;
import com.premd.interviewloop.domain.TranscriptTurn;
import com.premd.interviewloop.domain.enums.ArtifactKind;
import com.premd.interviewloop.domain.enums.TurnRole;
import com.premd.interviewloop.domain.repository.ArtifactSnapshotRepository;
import com.premd.interviewloop.domain.repository.SessionRoundRepository;
import com.premd.interviewloop.domain.repository.TranscriptTurnRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * Append-only transcript and artifact persistence.
 * Turns and artifacts are never edited — replay works by re-playing events.
 */
@Service
public class TranscriptService {

    private static final Logger log = LoggerFactory.getLogger(TranscriptService.class);

    private final TranscriptTurnRepository turnRepo;
    private final ArtifactSnapshotRepository artifactRepo;
    private final SessionRoundRepository roundRepo;

    public TranscriptService(TranscriptTurnRepository turnRepo,
                             ArtifactSnapshotRepository artifactRepo,
                             SessionRoundRepository roundRepo) {
        this.turnRepo = turnRepo;
        this.artifactRepo = artifactRepo;
        this.roundRepo = roundRepo;
    }

    /**
     * Append a turn to the transcript. Auto-assigns the next ordinal.
     */
    @Transactional
    public TranscriptTurn appendTurn(Long roundId, TurnRole role, String content) {
        SessionRound round = roundRepo.findById(roundId)
                .orElseThrow(() -> new NoSuchElementException("Round not found: " + roundId));

        int nextOrdinal = turnRepo.countByRoundId(roundId) + 1;

        TranscriptTurn turn = new TranscriptTurn(round, nextOrdinal, role, content);
        turn = turnRepo.save(turn);

        log.debug("Turn {}.{} [{}]: {}",
                roundId, nextOrdinal, role,
                content.length() > 80 ? content.substring(0, 80) + "..." : content);

        return turn;
    }

    /**
     * Append a turn with latency tracking.
     */
    @Transactional
    public TranscriptTurn appendTurn(Long roundId, TurnRole role, String content, int latencyMs) {
        TranscriptTurn turn = appendTurn(roundId, role, content);
        turn.setLatencyMs(latencyMs);
        return turnRepo.save(turn);
    }

    /**
     * Save an artifact snapshot (code, diagram, etc.) linked to a turn.
     */
    @Transactional
    public ArtifactSnapshot saveArtifact(Long roundId, Long turnId,
                                          ArtifactKind kind, String language, String payload) {
        SessionRound round = roundRepo.findById(roundId)
                .orElseThrow(() -> new NoSuchElementException("Round not found: " + roundId));

        ArtifactSnapshot snapshot = new ArtifactSnapshot(round, kind, payload);
        snapshot.setLanguage(language);

        if (turnId != null) {
            TranscriptTurn turn = turnRepo.findById(turnId).orElse(null);
            snapshot.setTurn(turn);
        }

        snapshot = artifactRepo.save(snapshot);
        log.debug("Artifact {}.{} [{}]: {} bytes", roundId, snapshot.getId(), kind, payload.length());
        return snapshot;
    }

    /**
     * Get all turns for a round, ordered by ordinal (for replay).
     */
    public List<TranscriptTurn> getTranscript(Long roundId) {
        return turnRepo.findByRoundIdOrderByOrdinalAsc(roundId);
    }

    /**
     * Get all artifact snapshots for a round, ordered by time (for scrubbable replay).
     */
    public List<ArtifactSnapshot> getArtifacts(Long roundId) {
        return artifactRepo.findByRoundIdOrderByCreatedAtAsc(roundId);
    }
}
