package com.premd.interviewloop.domain.repository;

import com.premd.interviewloop.domain.Signal;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface SignalRepository extends JpaRepository<Signal, Long> {
    List<Signal> findByRoundIdOrderByIdAsc(Long roundId);
}
