package com.premd.interviewloop.domain.repository;

import com.premd.interviewloop.domain.SessionReport;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface SessionReportRepository extends JpaRepository<SessionReport, Long> {
    Optional<SessionReport> findBySessionId(Long sessionId);
}
