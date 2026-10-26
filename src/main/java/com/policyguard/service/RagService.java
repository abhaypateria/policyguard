package com.policyguard.service;

import com.policyguard.config.PolicyGuardProperties;
import com.policyguard.dto.*;
import com.policyguard.entity.PolicyDocument;
import com.policyguard.service.LlmGateway.LlmResult;
import com.policyguard.service.RetrievalService.RetrievedChunk;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * THE SELF-CORRECTING RAG LOOP.
 *
 *  SCOPE     which policy versions were in force on the as-of date?         (temporal grounding)
 *  RETRIEVE  hybrid search over only those versions
 *  GATE      is the best evidence above the calibrated similarity threshold?
 *  GRADE     LLM judges: relevant? sufficient? any conflicting sources?
 *  REWRITE   if not sufficient -> rewrite the query and RETRIEVE again (max 2 retries)
 *  REFUSE    still not sufficient -> "Not found in the policy documents" (never guess)
 *  GENERATE  answer strictly from labelled sources, citing [S1], [S2]...
 *  VERIFY    sentence-by-sentence check that each claim is supported by what it cites
 *  AUDIT     seal the whole exchange into the hash-chained audit log
 *
 * PLAIN mode runs only RETRIEVE + GENERATE once - the baseline we measure against.
 */
@Service
public class RagService {

    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    private static final String INSUFFICIENT = "INSUFFICIENT_EVIDENCE";

    private final RetrievalService retrieval;
    private final VersionService versions;
    private final LlmGateway llm;
    private final GroundingVerifier verifier;
    private final AuditService audit;
    private final ResponseCache cache;
    private final PolicyGuardProperties props;

    public RagService(RetrievalService retrieval, VersionService versions, LlmGateway llm, GroundingVerifier verifier,
                      AuditService audit, ResponseCache cache, PolicyGuardProperties props) {
        this.retrieval = retrieval;
        this.versions = versions;
        this.llm = llm;
        this.verifier = verifier;
        this.audit = audit;
        this.cache = cache;
        this.props = props;
    }

    /** A real user question: cached, and always written to the audit log. */
    public ChatResponse ask(ChatRequest request) {
        return run(request, true, props.cache().enabled());
    }

    /** Used by the evaluation harness: no cache, no audit records. */
    public ChatResponse evaluate(ChatRequest request) {
        return run(request, false, false);
    }

    private ChatResponse run(ChatRequest req, boolean audited, boolean useCache) {
        long start = System.currentTimeMillis();
        AnswerMode mode = req.mode() == null ? AnswerMode.SELF_CORRECTING : req.mode();
        LocalDate asOf = req.asOfDate() == null ? LocalDate.now(INDIA) : req.asOfDate();
        String user = req.userId() == null || req.userId().isBlank() ? "demo-user" : req.userId().trim();
        String question = req.question().trim();
        String cacheKey = mode + "|" + asOf + "|" + question.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");

        ChatResponse response = useCache ? cache.get(cacheKey) : null;
        if (response != null) {
            response = response.asCached(System.currentTimeMillis() - start);
        } else {
            response = mode == AnswerMode.UNGROUNDED
                    ? ungrounded(question, asOf, start)
                    : grounded(question, asOf, mode, start);
            if (useCache) cache.put(cacheKey, response);
        }
        return audited ? audit.seal(response, user) : response;
    }

    private ChatResponse ungrounded(String question, LocalDate asOf, long start) {
        LlmResult r = llm.call(Prompts.UNGROUNDED_SYSTEM, question);
        List<TraceStep> trace = List.of(new TraceStep("GENERATE",
                "LLM answered from its own training data - no documents consulted (" + r.provider() + ")", r.latencyMs()));
        return new ChatResponse(question, asOf, AnswerMode.UNGROUNDED, Outcome.ANSWERED, r.text(), null,
                List.of(), List.of(),
                List.of("Ungrounded answer: no bank documents were consulted, nothing is cited and nothing can be verified. "
                        + "This is what a public chatbot would tell your employee."),
                GroundingReport.empty(), trace, 1, r.provider(), System.currentTimeMillis() - start, null, null, false);
    }

    private ChatResponse grounded(String question, LocalDate asOf, AnswerMode mode, long start) {
        List<TraceStep> trace = new ArrayList<>();

        // ---- SCOPE: temporal grounding ----
        Map<UUID, PolicyDocument> inForce = versions.inForce(asOf);
        trace.add(new TraceStep("SCOPE", inForce.size() + " document version(s) in force on " + asOf
                + " - only these are searched", 0));
        if (inForce.isEmpty()) {
            return refusal(question, asOf, mode, List.of(), trace, 0, null, start,
                    "no policy documents were in force on " + asOf);
        }
        List<String> documentIds = inForce.keySet().stream().map(UUID::toString).toList();
        int maxAttempts = mode == AnswerMode.PLAIN ? 1 : Math.max(1, props.rag().maxAttempts());
        double tau = props.retrieval().minSimilarity();
        int topK = props.retrieval().topK();

        Map<String, RetrievedChunk> evidence = new LinkedHashMap<>();
        String query = question;
        Grade grade = null;
        String provider = null;
        int attempt = 0;

        while (attempt < maxAttempts) {
            attempt++;

            // ---- RETRIEVE ----
            long t = System.currentTimeMillis();
            List<RetrievedChunk> found = retrieval.retrieve(query, documentIds);
            found.forEach(c -> evidence.merge(c.id(), c, (a, b) -> a.similarity() >= b.similarity() ? a : b));
            trace.add(new TraceStep("RETRIEVE", String.format(Locale.ROOT,
                    "attempt %d: %d passages via %s search for \"%s\" (best similarity %.2f)",
                    attempt, found.size(), retrieval.mode(), abbreviate(query, 90), best(found)),
                    System.currentTimeMillis() - t));
            if (mode == AnswerMode.PLAIN) break;

            // ---- GATE: calibrated abstention threshold ----
            double bestSoFar = best(evidence.values());
            if (bestSoFar < tau) {
                trace.add(new TraceStep("GATE", String.format(Locale.ROOT,
                        "best similarity %.2f is below the calibrated threshold %.2f - evidence too weak", bestSoFar, tau), 0));
                if (attempt < maxAttempts) {
                    LlmResult rw = llm.call(Prompts.REWRITE_SYSTEM, Prompts.rewriteUser(question, query, "no relevant passages"));
                    provider = rw.provider();
                    query = cleanQuery(rw.text(), question);
                    trace.add(new TraceStep("REWRITE", "retrying with: \"" + query + "\"", rw.latencyMs()));
                    continue;
                }
                break;
            }

            // ---- GRADE ----
            List<RetrievedChunk> top = topEvidence(evidence, topK);
            List<Citation> labelled = label(top, inForce);
            LlmResult g = llm.call(Prompts.GRADER_SYSTEM, Prompts.sourcesUser(question, labelled, top));
            provider = g.provider();
            grade = Grade.parse(g.text());
            trace.add(new TraceStep("GRADE", describe(grade), g.latencyMs()));
            if (grade.sufficient()) break;

            // ---- REWRITE and loop ----
            if (attempt < maxAttempts) {
                String next = grade.rewrittenQuery();
                long rewriteMs = 0;
                if (next == null || next.isBlank() || next.equalsIgnoreCase(query)) {
                    LlmResult rw = llm.call(Prompts.REWRITE_SYSTEM, Prompts.rewriteUser(question, query, grade.missing()));
                    next = rw.text();
                    rewriteMs = rw.latencyMs();
                }
                query = cleanQuery(next, question);
                trace.add(new TraceStep("REWRITE", "retrying with: \"" + query + "\"", rewriteMs));
            }
        }

        List<RetrievedChunk> finalChunks = topEvidence(evidence, topK);
        List<Citation> sources = label(finalChunks, inForce);
        double best = best(evidence.values());

        // ---- REFUSE (never guess) ----
        String refuseReason = null;
        if (finalChunks.isEmpty()) {
            refuseReason = "no passage matched the question";
        } else if (mode == AnswerMode.SELF_CORRECTING && best < tau) {
            refuseReason = String.format(Locale.ROOT, "best evidence similarity %.2f stayed below the threshold %.2f", best, tau);
        } else if (mode == AnswerMode.SELF_CORRECTING && grade != null && !grade.sufficient()) {
            refuseReason = "the evidence grader judged the passages insufficient"
                    + (grade.missing().isBlank() ? "" : " (missing: " + grade.missing() + ")");
        }
        if (refuseReason != null) {
            trace.add(new TraceStep("REFUSE", refuseReason, 0));
            return refusal(question, asOf, mode, sources, trace, attempt, provider, start, refuseReason);
        }

        // ---- GENERATE ----
        LlmResult gen = llm.call(Prompts.answerSystem(asOf), Prompts.sourcesUser(question, sources, finalChunks));
        provider = gen.provider();
        trace.add(new TraceStep("GENERATE", "grounded answer written by " + gen.provider() + " from "
                + sources.size() + " labelled sources", gen.latencyMs()));
        String answer = gen.text().trim();
        if (answer.contains(INSUFFICIENT)) {
            trace.add(new TraceStep("REFUSE", "the model itself found the sources insufficient", 0));
            return refusal(question, asOf, mode, sources, trace, attempt, provider, start,
                    "the model found the retrieved sources insufficient");
        }

        // ---- VERIFY ----
        Map<String, String> textByLabel = new HashMap<>();
        for (int i = 0; i < sources.size(); i++) textByLabel.put(sources.get(i).label(), finalChunks.get(i).body());
        GroundingReport grounding = verifier.verify(answer, textByLabel);
        trace.add(new TraceStep("VERIFY", grounding.supported() + "/" + grounding.claims()
                + " claims supported by the source they cite", 0));

        Set<String> cited = GroundingVerifier.citedLabels(answer);
        List<Citation> marked = sources.stream().map(c -> c.withCited(cited.contains(c.label()))).toList();

        // Keep a conflict if it names no labels (described in prose) or all its labels are real sources.
        List<Conflict> conflicts = grade == null ? List.of() : grade.conflicts().stream()
                .filter(c -> c.sources().stream().allMatch(textByLabel::containsKey))
                .toList();
        if (!conflicts.isEmpty()) {
            trace.add(new TraceStep("CONFLICT", conflicts.size() + " conflict(s) between sources flagged for review", 0));
        }

        Set<PolicyDocument> citedDocuments = new LinkedHashSet<>();
        for (Citation c : marked) {
            if (c.cited()) {
                PolicyDocument d = inForce.get(UUID.fromString(c.documentId()));
                if (d != null) citedDocuments.add(d);
            }
        }
        List<String> notices = versions.supersessionNotices(citedDocuments, asOf);

        return new ChatResponse(question, asOf, mode, Outcome.ANSWERED, answer,
                confidence(best, tau, grounding.score(), grade, attempt),
                marked, conflicts, notices, grounding, trace, attempt, provider,
                System.currentTimeMillis() - start, null, null, false);
    }

    private ChatResponse refusal(String question, LocalDate asOf, AnswerMode mode, List<Citation> closest,
                                 List<TraceStep> trace, int attempts, String provider, long start, String reason) {
        String text = "Not found in the policy documents. I could not find sufficient evidence in the policies in force on "
                + asOf + " to answer this reliably, so I will not guess (" + reason + "). "
                + "Please refer this question to the Compliance team."
                + (closest.isEmpty() ? "" : " The closest passages I found are listed below for reference.");
        return new ChatResponse(question, asOf, mode, Outcome.REFUSED, text, null, closest, List.of(), List.of(),
                GroundingReport.empty(), trace, attempts, provider, System.currentTimeMillis() - start, null, null, false);
    }

    /** Label chunks S1..Sn with everything needed for a citation. */
    private List<Citation> label(List<RetrievedChunk> chunks, Map<UUID, PolicyDocument> inForce) {
        List<Citation> out = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            RetrievedChunk c = chunks.get(i);
            PolicyDocument d = inForce.get(UUID.fromString(c.documentId()));
            out.add(new Citation("S" + (i + 1), c.id(), c.documentId(),
                    d == null ? String.valueOf(c.metadata().get("title")) : d.getTitle(),
                    d == null ? String.valueOf(c.metadata().get("issuer")) : d.getIssuer(),
                    d == null ? String.valueOf(c.metadata().get("sourceType")) : d.getSourceType().name(),
                    d == null ? String.valueOf(c.metadata().get("documentFamily")) : d.getDocumentFamily(),
                    d == null ? String.valueOf(c.metadata().get("versionLabel")) : d.getVersionLabel(),
                    d == null ? null : d.getEffectiveFrom(),
                    d == null ? null : d.getEffectiveTo(),
                    c.page(), abbreviate(c.body(), 420), Math.round(c.similarity() * 100) / 100.0, false));
        }
        return out;
    }

    /**
     * Rank the evidence gathered across all attempts: semantic similarity, plus a boost for chunks
     * that BOTH searches agreed on (high fused RRF score).
     */
    private static List<RetrievedChunk> topEvidence(Map<String, RetrievedChunk> evidence, int k) {
        return evidence.values().stream()
                .sorted(Comparator.comparingDouble((RetrievedChunk c) -> c.similarity() + 5 * c.fusedScore()).reversed())
                .limit(k)
                .toList();
    }

    private static double best(Collection<RetrievedChunk> chunks) {
        return chunks.stream().mapToDouble(RetrievedChunk::similarity).max().orElse(0.0);
    }

    /**
     * Confidence (0-1) = 35% retrieval strength + 45% groundedness + 20% grader verdict - 5% per retry.
     * Every term is observable in the trace, so a reviewer can see exactly why a number is what it is.
     */
    static double confidence(double best, double tau, double grounding, Grade grade, int attempts) {
        double retrievalStrength = clamp((best - tau) / Math.max(0.05, 0.75 - tau));
        double graderScore = grade == null || !grade.parsed() ? 0.6 : (grade.sufficient() ? 1.0 : 0.3);
        double c = 0.35 * retrievalStrength + 0.45 * grounding + 0.20 * graderScore - 0.05 * (attempts - 1);
        return Math.round(clamp(c) * 100) / 100.0;
    }

    private static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    private static String describe(Grade g) {
        if (!g.parsed()) return "grader reply unreadable - relying on the similarity gate and answer-level abstention";
        if (g.sufficient()) {
            return "evidence is relevant and sufficient" + (g.conflicts().isEmpty() ? "" : "; conflicting sources detected");
        }
        return (g.relevant() ? "relevant but insufficient" : "not relevant")
                + (g.missing().isBlank() ? "" : " - missing: " + abbreviate(g.missing(), 140));
    }

    private static String cleanQuery(String raw, String fallback) {
        if (raw == null) return fallback;
        String line = raw.lines().map(String::trim).filter(s -> !s.isEmpty()).findFirst().orElse("");
        line = line.replaceFirst("(?i)^(search )?query:\\s*", "").replaceAll("^[\"'`]+|[\"'`]+$", "").trim();
        return line.isBlank() ? fallback : abbreviate(line, 200);
    }

    private static String abbreviate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }
}
