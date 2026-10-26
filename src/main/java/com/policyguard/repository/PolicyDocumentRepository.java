package com.policyguard.repository;

import com.policyguard.entity.DocumentStatus;
import com.policyguard.entity.PolicyDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PolicyDocumentRepository extends JpaRepository<PolicyDocument, UUID> {

    Optional<PolicyDocument> findBySha256(String sha256);

    List<PolicyDocument> findByDocumentFamilyAndStatusNotOrderByEffectiveFromAsc(String family, DocumentStatus excluded);

    List<PolicyDocument> findAllByOrderByDocumentFamilyAscEffectiveFromAsc();

    long countByStatus(DocumentStatus status);

    /** The heart of temporal retrieval: which document versions were in force on a given date. */
    @Query("""
            select d from PolicyDocument d
            where d.status = :status
              and d.effectiveFrom <= :asOf
              and (d.effectiveTo is null or d.effectiveTo > :asOf)
            """)
    List<PolicyDocument> findInForceAsOf(@Param("asOf") LocalDate asOf, @Param("status") DocumentStatus status);
}
