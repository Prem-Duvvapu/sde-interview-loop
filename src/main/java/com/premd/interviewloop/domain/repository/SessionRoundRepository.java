package com.premd.interviewloop.domain.repository;

import com.premd.interviewloop.domain.SessionRound;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface SessionRoundRepository extends JpaRepository<SessionRound, Long> {
    List<SessionRound> findBySessionIdOrderByOrdinalAsc(Long sessionId);

    /**
     * Evaluation runs outside the transaction that completes a round. Fetch the parent here
     * rather than dereferencing the LAZY association after that transaction has closed.
     */
    @Query("SELECT r FROM SessionRound r JOIN FETCH r.session WHERE r.id = :id")
    Optional<SessionRound> findByIdWithSession(@Param("id") Long id);
}
