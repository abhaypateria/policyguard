package com.policyguard.repository;

import com.policyguard.entity.EvalRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface EvalRunRepository extends JpaRepository<EvalRun, UUID> {

    Optional<EvalRun> findTopByStatusOrderByStartedAtDesc(String status);

    Optional<EvalRun> findTopByOrderByStartedAtDesc();
}
