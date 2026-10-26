package com.policyguard.dto;

import com.policyguard.entity.PolicyDocument;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** What the API returns for a document - never the raw bytes. */
public record DocumentDto(
        UUID id,
        String title,
        String fileName,
        String issuer,
        String sourceType,
        String documentFamily,
        String versionLabel,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        UUID supersededBy,
        boolean current,
        String status,
        Integer pageCount,
        Integer chunkCount,
        String sha256,
        Long sizeBytes,
        String errorMessage,
        String uploadedBy,
        Instant uploadedAt,
        Instant indexedAt) {

    public static DocumentDto from(PolicyDocument d) {
        return new DocumentDto(d.getId(), d.getTitle(), d.getFileName(), d.getIssuer(), d.getSourceType().name(),
                d.getDocumentFamily(), d.getVersionLabel(), d.getEffectiveFrom(), d.getEffectiveTo(), d.getSupersededBy(),
                d.getEffectiveTo() == null, d.getStatus().name(), d.getPageCount(), d.getChunkCount(), d.getSha256(),
                d.getSizeBytes(), d.getErrorMessage(), d.getUploadedBy(), d.getUploadedAt(), d.getIndexedAt());
    }
}
