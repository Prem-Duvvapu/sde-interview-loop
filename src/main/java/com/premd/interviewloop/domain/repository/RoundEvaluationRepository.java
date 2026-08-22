package com.premd.interviewloop.domain.repository;

import com.premd.interviewloop.domain.RoundEvaluation;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface RoundEvaluationRepository extends JpaRepository<RoundEvaluation, Long> {
    Optional<RoundEvaluation> findByRoundId(Long roundId);
}
