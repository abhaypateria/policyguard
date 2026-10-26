package com.policyguard.repository;

import com.policyguard.entity.AuditRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AuditRecordRepository extends JpaRepository<AuditRecord, Long> {

    Optional<AuditRecord> findTopByOrderBySeqDesc();

    Page<AuditRecord> findAllByOrderBySeqAsc(Pageable pageable);

    Page<AuditRecord> findAllByOrderBySeqDesc(Pageable pageable);
}
