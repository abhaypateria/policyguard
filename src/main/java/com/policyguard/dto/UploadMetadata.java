package com.policyguard.dto;

import com.policyguard.entity.SourceType;

import java.time.LocalDate;

/**
 * Metadata supplied with an upload. documentFamily groups versions of the same policy;
 * effectiveFrom is when this version came into force.
 */
public record UploadMetadata(
        String title,
        String issuer,
        SourceType sourceType,
        String documentFamily,
        String versionLabel,
        LocalDate effectiveFrom) {
}
