package com.premd.interviewloop.domain.repository;

import com.premd.interviewloop.domain.ArtifactSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ArtifactSnapshotRepository extends JpaRepository<ArtifactSnapshot, Long> {
    List<ArtifactSnapshot> findByRoundIdOrderByCreatedAtAsc(Long roundId);
}
