package com.premd.interviewloop.domain.repository;

import com.premd.interviewloop.domain.SessionRound;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface SessionRoundRepository extends JpaRepository<SessionRound, Long> {
    List<SessionRound> findBySessionIdOrderByOrdinalAsc(Long sessionId);
}
