package com.premd.interviewloop.domain.repository;

import com.premd.interviewloop.domain.InterviewSession;
import com.premd.interviewloop.domain.enums.SessionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface InterviewSessionRepository extends JpaRepository<InterviewSession, Long> {
    List<InterviewSession> findByStatusOrderByStartedAtDesc(SessionStatus status);
    List<InterviewSession> findByCompanyProfileIdOrderByStartedAtDesc(String companyProfileId);
    List<InterviewSession> findAllByOrderByStartedAtDesc();
}
