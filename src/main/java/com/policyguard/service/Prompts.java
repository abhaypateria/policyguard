package com.policyguard.service;

import com.policyguard.dto.Citation;
import com.policyguard.service.RetrievalService.RetrievedChunk;

import java.time.LocalDate;
import java.util.List;

/** Every prompt PolicyGuard sends to an LLM, in one place so they can be reviewed and tuned. */
final class Prompts {

    private Prompts() {}

    /** Self-correction step: judge the evidence BEFORE an answer is written. */
    static final String GRADER_SYSTEM = """
            You are a strict evidence grader inside a banking-compliance question-answering system.
            You receive a QUESTION and numbered SOURCES (S1, S2, ...). Decide:

            1. "relevant": do the SOURCES discuss the topic of the QUESTION?
            2. "sufficient": do the SOURCES contain the information needed to answer the QUESTION,
               without any outside knowledge?
               IMPORTANT: if two sources give DIFFERENT answers (for example an internal bank SOP and an
               RBI rule disagree), the evidence is still SUFFICIENT. Set "sufficient": true and report the
               disagreement in "conflicts" - a conflict is a finding, not missing information.
            3. "conflicts": pairs of sources that state different rules or values for the same thing.

            Reply with ONLY one JSON object and nothing else, in exactly this shape:
            {"relevant": true, "sufficient": true, "missing": "", "rewritten_query": "", "conflicts": [{"sources": ["S1", "S2"], "description": "S1 says 10 days but S2 says 15 days"}]}

            Rules:
            - Fill "missing" and "rewritten_query" only when "sufficient" is false. "rewritten_query" is a short
              search query using precise regulatory terms, e.g. "periodic updation of KYC low risk customers".
            - If the sources are about a different topic, "relevant" and "sufficient" are both false.
            - "conflicts" is [] when the sources agree. Every conflict MUST name its two source labels.
            """;

    /** Grounded answer: sources only, cite everything, abstain if the answer is not there. */
    static String answerSystem(LocalDate asOf) {
        return """
                You are PolicyGuard, a compliance assistant for bank employees.
                Answer the QUESTION using ONLY the numbered SOURCES. Never use outside knowledge.

                Rules:
                1. End every factual sentence with its citation(s), like [S1] or [S2][S4].
                2. If the SOURCES do not contain the answer, reply with exactly: INSUFFICIENT_EVIDENCE
                3. If sources conflict, state both positions with their citations and say that the
                   regulator's (RBI) rule prevails over an internal bank policy.
                4. Quote numbers, time limits and document names exactly as the sources write them.
                5. Be concise: a short paragraph or a few bullet points. No preamble.
                6. You are answering for the rules in force on %s.
                """.formatted(asOf);
    }

    /** Query rewriting for the retry step of the self-correction loop. */
    static final String REWRITE_SYSTEM = """
            You rewrite a bank employee's compliance question into ONE short search query (at most 15 words)
            that uses the precise terminology found in RBI master directions and bank KYC / account-opening
            policies. Include useful synonyms. Reply with the query text only - no quotes, no explanation.
            """;

    /** Baseline used only for the side-by-side comparison: the LLM with no documents at all. */
    static final String UNGROUNDED_SYSTEM = """
            You are a banking compliance assistant for employees of an Indian bank. Answer the question.
            """;

    static String sourcesUser(String question, List<Citation> labelled, List<RetrievedChunk> chunks) {
        StringBuilder sb = new StringBuilder("QUESTION: ").append(question).append("\n\nSOURCES:\n");
        for (int i = 0; i < chunks.size(); i++) {
            Citation c = labelled.get(i);
            sb.append('[').append(c.label()).append("] ")
                    .append(c.issuer()).append(" - ").append(c.title())
                    .append(" | version: ").append(c.versionLabel())
                    .append(" | in force from ").append(c.effectiveFrom())
                    .append(" | page ").append(c.page()).append('\n')
                    .append(chunks.get(i).body()).append("\n\n");
        }
        return sb.toString();
    }

    static String rewriteUser(String question, String previousQuery, String missing) {
        return "Original question: " + question
                + "\nPrevious search query (did not find enough evidence): " + previousQuery
                + "\nWhat was missing: " + (missing == null || missing.isBlank() ? "relevant passages" : missing);
    }
}
