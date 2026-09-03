package com.premd.interviewloop.domain.repository;

import com.premd.interviewloop.domain.Resume;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ResumeRepository extends JpaRepository<Resume, Long> {
    Optional<Resume> findFirstByOrderByUploadedAtDesc();

    /** Pins a round to the resume text it started with, even if a newer one is uploaded mid-round. */
    Optional<Resume> findFirstByContentHashOrderByUploadedAtDesc(String contentHash);
}
