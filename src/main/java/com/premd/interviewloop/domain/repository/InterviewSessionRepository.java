package com.premd.interviewloop.domain.repository;

import com.premd.interviewloop.domain.InterviewSession;
import com.premd.interviewloop.domain.enums.SessionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface InterviewSessionRepository extends JpaRepository<InterviewSession, Long> {
    List<InterviewSession> findByStatusOrderByStartedAtDesc(SessionStatus status);
    List<InterviewSession> findByCompanyProfileIdOrderByStartedAtDesc(String companyProfileId);
    List<InterviewSession> findAllByOrderByStartedAtDesc();

    /**
     * {@code rounds} is LAZY and open-in-view is disabled, so a plain findById/findAll would
     * hand the controller a proxy that throws LazyInitializationException the moment Jackson
     * tries to serialise it — the Hibernate session is long closed by then. Fetch-join instead
     * of widening the transaction boundary or flipping the association to EAGER everywhere.
     */
    @Query("SELECT DISTINCT s FROM InterviewSession s LEFT JOIN FETCH s.rounds WHERE s.id = :id")
    Optional<InterviewSession> findByIdWithRounds(@Param("id") Long id);

    @Query("SELECT DISTINCT s FROM InterviewSession s LEFT JOIN FETCH s.rounds ORDER BY s.startedAt DESC")
    List<InterviewSession> findAllWithRoundsByOrderByStartedAtDesc();
}
