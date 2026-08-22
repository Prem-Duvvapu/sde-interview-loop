package com.premd.interviewloop.domain.repository;

import com.premd.interviewloop.domain.ReadinessSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ReadinessSnapshotRepository extends JpaRepository<ReadinessSnapshot, Long> {
    List<ReadinessSnapshot> findByCompanyProfileIdAndModuleTypeOrderByTakenAtDesc(
            String companyProfileId, String moduleType);
    List<ReadinessSnapshot> findByCompanyProfileIdOrderByTakenAtDesc(String companyProfileId);
}
