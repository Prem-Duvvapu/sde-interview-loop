package com.premd.interviewloop.domain.repository;

import com.premd.interviewloop.domain.LlmCall;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;

public interface LlmCallRepository extends JpaRepository<LlmCall, Long> {
    List<LlmCall> findByRoundIdOrderByCreatedAtAsc(Long roundId);

    @Query("SELECT SUM(l.costEstimateUsd) FROM LlmCall l WHERE l.round.id = :roundId")
    Double sumCostByRoundId(Long roundId);

    @Query("SELECT SUM(l.costEstimateUsd) FROM LlmCall l WHERE l.round.session.id = :sessionId")
    Double sumCostBySessionId(Long sessionId);
}
