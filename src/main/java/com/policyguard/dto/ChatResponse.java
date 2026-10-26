package com.policyguard.dto;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Everything the UI needs to show an answer AND justify it:
 * the answer, its sources, a confidence score, conflicts, version notices,
 * the step-by-step trace of the self-correction loop, and the audit record that sealed it.
 */
public record ChatResponse(
        String question,
        LocalDate asOfDate,
        AnswerMode mode,
        Outcome outcome,
        String answer,
        Double confidence,
        List<Citation> sources,
        List<Conflict> conflicts,
        List<String> notices,
        GroundingReport grounding,
        List<TraceStep> trace,
        int attempts,
        String provider,
        long latencyMs,
        Long auditSeq,
        String auditHash,
        boolean cached) {

    /** Copy with the audit record attached (records are immutable, so we build a new one). */
    public ChatResponse withAudit(long seq, String hash) {
        List<TraceStep> t = new ArrayList<>(trace);
        t.add(new TraceStep("AUDIT", "sealed as record #" + seq + " (hash " + hash.substring(0, 12) + "...)", 0));
        return new ChatResponse(question, asOfDate, mode, outcome, answer, confidence, sources, conflicts, notices,
                grounding, t, attempts, provider, latencyMs, seq, hash, cached);
    }

    /** Copy marked as served from the answer cache. */
    public ChatResponse asCached(long newLatencyMs) {
        List<TraceStep> t = new ArrayList<>(trace);
        t.removeIf(s -> s.step().equals("AUDIT"));
        t.add(new TraceStep("CACHE", "identical question answered recently - served from cache, no LLM call", 0));
        return new ChatResponse(question, asOfDate, mode, outcome, answer, confidence, sources, conflicts, notices,
                grounding, t, attempts, provider, newLatencyMs, null, null, true);
    }
}
