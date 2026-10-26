package com.policyguard.dto;

import java.time.LocalDate;

/**
 * One source passage shown to the LLM, labelled S1, S2 ...
 * "cited" says whether the final answer actually referenced it.
 */
public record Citation(
        String label,
        String chunkId,
        String documentId,
        String title,
        String issuer,
        String sourceType,
        String documentFamily,
        String versionLabel,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        Integer page,
        String snippet,
        double similarity,
        boolean cited) {

    public Citation withCited(boolean value) {
        return new Citation(label, chunkId, documentId, title, issuer, sourceType, documentFamily, versionLabel,
                effectiveFrom, effectiveTo, page, snippet, similarity, value);
    }
}
