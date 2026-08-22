package com.premd.interviewloop.domain.repository;

import com.premd.interviewloop.domain.TranscriptTurn;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface TranscriptTurnRepository extends JpaRepository<TranscriptTurn, Long> {
    List<TranscriptTurn> findByRoundIdOrderByOrdinalAsc(Long roundId);
    int countByRoundId(Long roundId);
}
