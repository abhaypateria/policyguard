package com.policyguard.dto;

import java.util.List;

/**
 * Sentence-level check of the answer against its own citations.
 *   SUPPORTED = the sentence cites a source and shares most of its key terms with it
 *   WEAK      = it cites a source, but the source does not clearly contain the claim
 *   UNCITED   = a factual sentence with no citation at all
 */
public record GroundingReport(int claims, int supported, int weak, int uncited, double score, List<ClaimCheck> sentences) {

    public record ClaimCheck(String text, String status, double support, List<String> citations) {
    }

    public static GroundingReport empty() {
        return new GroundingReport(0, 0, 0, 0, 0.0, List.of());
    }
}
