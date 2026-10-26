package com.policyguard.repository;

import com.policyguard.entity.DocumentFile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface DocumentFileRepository extends JpaRepository<DocumentFile, UUID> {
}
