package com.policyguard.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.policyguard.config.PolicyGuardProperties;
import com.policyguard.dto.*;
import com.policyguard.entity.EvalRun;
import com.policyguard.exception.ApiException;
import com.policyguard.repository.EvalRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Predicate;

/**
 * EVALUATION HARNESS - proof, not claims.
 *
 * Every question in eval/gold.json is answered twice, by PLAIN RAG (retrieve once, answer once)
 * and by SELF-CORRECTING PolicyGuard, on the SAME pinned LLM. Each question is labelled with a
 * category and its expected evidence:
 *   ANSWERABLE   - answer exists in the corpus (expected document + keywords)
 *   TEMPORAL     - the correct answer depends on the as-of date (expected version)
 *   CONFLICT     - an internal SOP disagrees with the RBI rule (should be flagged)
 *   UNANSWERABLE - the corpus does not contain the answer (the ONLY correct behaviour is refusal)
 *
 * It also sweeps the similarity threshold to show how the abstention threshold was calibrated.
 */
@Service
public class EvalService {

    private static final Logger log = LoggerFactory.getLogger(EvalService.class);

    public record Expect(String family, String version, List<Integer> pages, List<String> keywords) {}

    public record GoldItem(String id, String category, String question, LocalDate asOfDate,
                           boolean shouldRefuse, Expect expect) {}

    public record ItemResult(String id, String category, String mode, String outcome, boolean correct,
                             boolean retrievalHit, double bestSimilarity, int conflicts, double groundedness,
                             List<String> citedVersions, long latencyMs, String provider, String answer) {}

    private final RagService rag;
    private final LlmGateway llm;
    private final EvalRunRepository runs;
    private final ObjectMapper mapper;
    private final PolicyGuardProperties props;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "eval-runner"));
    private volatile UUID running;

    public EvalService(RagService rag, LlmGateway llm, EvalRunRepository runs, ObjectMapper mapper,
                       PolicyGuardProperties props) {
        this.rag = rag;
        this.llm = llm;
        this.runs = runs;
        this.mapper = mapper;
        this.props = props;
    }

    public List<GoldItem> gold() {
        try (InputStream in = new ClassPathResource("eval/gold.json").getInputStream()) {
            return mapper.readValue(in, new TypeReference<>() {});
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read eval/gold.json", e);
        }
    }

    public synchronized EvalRun start(String provider, Integer limit) {
        if (!props.eval().enabled()) throw ApiException.badRequest("Evaluation runs are disabled on this deployment");
        if (running != null) throw ApiException.conflict("An evaluation is already running: " + running);
        EvalRun run = new EvalRun();
        run.setId(UUID.randomUUID());
        run.setStartedAt(Instant.now());
        run.setStatus("RUNNING");
        run.setProvider(provider);
        runs.save(run);
        running = run.getId();
        worker.submit(() -> execute(run.getId(), provider, limit));
        return run;
    }

    private void execute(UUID runId, String provider, Integer limit) {
        EvalRun run = runs.findById(runId).orElseThrow();
        try {
            List<GoldItem> items = gold();
            if (limit != null && limit > 0 && limit < items.size()) items = items.subList(0, limit);
            List<ItemResult> results = new ArrayList<>();
            for (GoldItem item : items) {
                for (AnswerMode mode : List.of(AnswerMode.PLAIN, AnswerMode.SELF_CORRECTING)) {
                    results.add(runOne(item, mode, provider));
                }
                log.info("Eval {}: {} done", runId, item.id());
            }
            Map<String, Object> summary = summarise(items, results);
            run.setSummaryJson(mapper.writeValueAsString(summary));
            run.setDetailsJson(mapper.writeValueAsString(results));
            run.setMarkdown(markdown(items, results, summary, provider));
            run.setStatus("DONE");
            writeReport(run.getMarkdown());
        } catch (Exception e) {
            log.error("Evaluation failed", e);
            run.setStatus("FAILED");
            run.setSummaryJson("{\"error\":" + quote(e.getMessage()) + "}");
        } finally {
            run.setFinishedAt(Instant.now());
            runs.save(run);
            running = null;
        }
    }

    private ItemResult runOne(GoldItem item, AnswerMode mode, String provider) {
        ChatRequest req = new ChatRequest(item.question(), item.asOfDate(), mode, "eval-harness");
        long start = System.currentTimeMillis();
        try {
            ChatResponse r = provider == null || provider.isBlank()
                    ? rag.evaluate(req)
                    : llm.withProvider(provider, () -> rag.evaluate(req));
            boolean refused = r.outcome() == Outcome.REFUSED;
            boolean hit = item.expect() != null && r.sources().stream().anyMatch(matches(item.expect()));
            boolean correct = item.shouldRefuse() ? refused : (!refused && keywordsPresent(r.answer(), item.expect())
                    && versionCorrect(r, item));
            double best = r.sources().stream().mapToDouble(Citation::similarity).max().orElse(0);
            List<String> cited = r.sources().stream().filter(Citation::cited)
                    .map(c -> c.documentFamily() + "@" + c.versionLabel()).distinct().toList();
            return new ItemResult(item.id(), item.category(), mode.name(), r.outcome().name(), correct, hit, best,
                    r.conflicts().size(), r.grounding().score(), cited, r.latencyMs(), r.provider(),
                    abbreviate(r.answer(), 300));
        } catch (Exception e) {
            return new ItemResult(item.id(), item.category(), mode.name(), "ERROR", false, false, 0, 0, 0, List.of(),
                    System.currentTimeMillis() - start, provider, abbreviate(String.valueOf(e.getMessage()), 300));
        }
    }

    /** Correct document family (+ version and page when specified). "A|B" accepts either family. */
    private static Predicate<Citation> matches(Expect e) {
        return c -> familyMatches(e.family(), c.documentFamily())
                && (e.version() == null || e.version().equalsIgnoreCase(c.versionLabel()))
                && (e.pages() == null || e.pages().isEmpty() || (c.page() != null && e.pages().contains(c.page())));
    }

    private static boolean familyMatches(String expected, String actual) {
        return expected == null
                || Arrays.stream(expected.split("\\|")).anyMatch(f -> f.trim().equalsIgnoreCase(actual));
    }

    /** Each keyword group must appear; "a|b" means either alternative is accepted. */
    static boolean keywordsPresent(String answer, Expect e) {
        if (e == null || e.keywords() == null || e.keywords().isEmpty()) return true;
        String a = answer.toLowerCase(Locale.ROOT);
        return e.keywords().stream().allMatch(group ->
                Arrays.stream(group.toLowerCase(Locale.ROOT).split("\\|")).anyMatch(alt -> a.contains(alt.trim())));
    }

    /** For temporal questions, every cited chunk of the family must come from the expected version. */
    private static boolean versionCorrect(ChatResponse r, GoldItem item) {
        Expect e = item.expect();
        if (e == null || e.version() == null) return true;
        return r.sources().stream().filter(Citation::cited)
                .filter(c -> familyMatches(e.family(), c.documentFamily()))
                .allMatch(c -> e.version().equalsIgnoreCase(c.versionLabel()));
    }

    // ------------------------------------------------------------------ metrics

    private Map<String, Object> summarise(List<GoldItem> items, List<ItemResult> results) {
        Map<String, Object> summary = new LinkedHashMap<>();
        for (String mode : List.of("PLAIN", "SELF_CORRECTING")) {
            summary.put(mode, metrics(items, results.stream().filter(r -> r.mode().equals(mode)).toList()));
        }
        summary.put("thresholdSweep", sweep(items, results.stream().filter(r -> r.mode().equals("PLAIN")).toList()));
        summary.put("questions", items.size());
        return summary;
    }

    private Map<String, Object> metrics(List<GoldItem> items, List<ItemResult> rs) {
        Map<String, GoldItem> byId = new HashMap<>();
        items.forEach(i -> byId.put(i.id(), i));
        List<ItemResult> answerable = rs.stream().filter(r -> !byId.get(r.id()).shouldRefuse()).toList();
        List<ItemResult> unanswerable = rs.stream().filter(r -> byId.get(r.id()).shouldRefuse()).toList();
        List<ItemResult> temporal = rs.stream().filter(r -> r.category().equals("TEMPORAL")).toList();
        List<ItemResult> conflict = rs.stream().filter(r -> r.category().equals("CONFLICT")).toList();
        List<ItemResult> answered = rs.stream().filter(r -> r.outcome().equals("ANSWERED")).toList();

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("overallCorrect", pct(rs, ItemResult::correct));
        m.put("answerAccuracy", pct(answerable, ItemResult::correct));
        m.put("retrievalHitRate", pct(answerable, ItemResult::retrievalHit));
        m.put("correctRefusalRate", pct(unanswerable, r -> r.outcome().equals("REFUSED")));
        m.put("hallucinationRate", pct(unanswerable, r -> r.outcome().equals("ANSWERED")));
        m.put("falseRefusalRate", pct(answerable, r -> r.outcome().equals("REFUSED")));
        m.put("temporalAccuracy", pct(temporal, ItemResult::correct));
        m.put("conflictDetectionRate", pct(conflict, r -> r.conflicts() > 0));
        m.put("avgGroundedness", round(answered.stream().mapToDouble(ItemResult::groundedness).average().orElse(0)));
        m.put("avgLatencyMs", Math.round(rs.stream().mapToLong(ItemResult::latencyMs).average().orElse(0)));
        m.put("p95LatencyMs", percentile(rs.stream().mapToLong(ItemResult::latencyMs).sorted().toArray(), 0.95));
        m.put("errors", rs.stream().filter(r -> r.outcome().equals("ERROR")).count());
        return m;
    }

    /** Calibration: for each threshold, what fraction of unanswerable vs answerable questions would be refused. */
    private List<Map<String, Object>> sweep(List<GoldItem> items, List<ItemResult> plain) {
        Map<String, GoldItem> byId = new HashMap<>();
        items.forEach(i -> byId.put(i.id(), i));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int t = 15; t <= 60; t += 5) {
            double tau = t / 100.0;
            List<ItemResult> un = plain.stream().filter(r -> byId.get(r.id()).shouldRefuse()).toList();
            List<ItemResult> ans = plain.stream().filter(r -> !byId.get(r.id()).shouldRefuse()).toList();
            double cr = pct(un, r -> r.bestSimilarity() < tau);
            double fr = pct(ans, r -> r.bestSimilarity() < tau);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("threshold", tau);
            row.put("correctRefusal", cr);
            row.put("falseRefusal", fr);
            row.put("youdenJ", round(cr - fr));
            rows.add(row);
        }
        return rows;
    }

    // ------------------------------------------------------------------ report

    @SuppressWarnings("unchecked")
    private String markdown(List<GoldItem> items, List<ItemResult> results, Map<String, Object> summary, String provider) {
        Map<String, Object> p = (Map<String, Object>) summary.get("PLAIN");
        Map<String, Object> s = (Map<String, Object>) summary.get("SELF_CORRECTING");
        StringBuilder md = new StringBuilder();
        md.append("# Evaluation Results\n\n")
                .append("> Generated automatically by PolicyGuard's evaluation harness on ").append(LocalDate.now())
                .append(". Re-run any time from the Evaluation page or `POST /api/eval/run`.\n\n")
                .append("## Setup\n\n")
                .append("| Item | Value |\n|---|---|\n")
                .append("| Questions | ").append(items.size()).append(" (").append(categoryCounts(items)).append(") |\n")
                .append("| LLM (same for both arms) | ").append(provider == null || provider.isBlank() ? "failover chain" : provider).append(" |\n")
                .append("| Embeddings | all-MiniLM-L6-v2, local ONNX, 384-d |\n")
                .append("| Retrieval | ").append(props.retrieval().hybrid() ? "hybrid dense + keyword (RRF)" : "dense")
                .append(", top-k ").append(props.retrieval().topK()).append(" |\n")
                .append("| Abstention threshold | ").append(props.retrieval().minSimilarity()).append(" |\n\n")
                .append("## Plain RAG vs self-correcting PolicyGuard\n\n")
                .append("| Metric | Plain RAG | PolicyGuard | What it means |\n|---|---|---|---|\n");
        row(md, "Overall correct behaviour", p, s, "overallCorrect", "right answer, or refusal when the answer is not in the corpus");
        row(md, "Answer accuracy (answerable)", p, s, "answerAccuracy", "expected facts present in the answer");
        row(md, "Retrieval hit rate", p, s, "retrievalHitRate", "the right document appeared in the evidence");
        row(md, "Correct refusal (unanswerable)", p, s, "correctRefusalRate", "said \"not found\" instead of guessing");
        row(md, "**Hallucination rate (unanswerable)**", p, s, "hallucinationRate", "answered a question the corpus cannot answer - lower is better");
        row(md, "False refusal (answerable)", p, s, "falseRefusalRate", "refused although the answer existed - lower is better");
        row(md, "Temporal accuracy", p, s, "temporalAccuracy", "used the version in force on the as-of date");
        row(md, "Conflict detection", p, s, "conflictDetectionRate", "flagged internal-vs-RBI contradictions");
        md.append("| Avg groundedness | ").append(p.get("avgGroundedness")).append(" | ").append(s.get("avgGroundedness"))
                .append(" | share of answer sentences supported by the source they cite |\n")
                .append("| Avg / p95 latency (ms) | ").append(p.get("avgLatencyMs")).append(" / ").append(p.get("p95LatencyMs"))
                .append(" | ").append(s.get("avgLatencyMs")).append(" / ").append(s.get("p95LatencyMs"))
                .append(" | the price of self-correction: extra LLM calls |\n\n");

        md.append("## Threshold calibration (abstention)\n\n")
                .append("Refusal decided by best-evidence similarity alone (first retrieval). ")
                .append("Youden's J = correct-refusal - false-refusal; the configured threshold is chosen near its maximum.\n\n")
                .append("| Threshold | Correct refusal | False refusal | J |\n|---|---|---|---|\n");
        for (Map<String, Object> r : (List<Map<String, Object>>) summary.get("thresholdSweep")) {
            md.append("| ").append(r.get("threshold")).append(" | ").append(r.get("correctRefusal")).append("% | ")
                    .append(r.get("falseRefusal")).append("% | ").append(r.get("youdenJ")).append(" |\n");
        }

        md.append("\n## Per-question results\n\n| ID | Category | Plain | PolicyGuard |\n|---|---|---|---|\n");
        for (GoldItem i : items) {
            md.append("| ").append(i.id()).append(" | ").append(i.category()).append(" | ")
                    .append(cell(results, i.id(), "PLAIN")).append(" | ").append(cell(results, i.id(), "SELF_CORRECTING")).append(" |\n");
        }
        md.append("\n## How to read this honestly\n\n")
                .append("- Correctness is checked with expected keywords and expected source documents - a strict, automatic proxy, not human grading.\n")
                .append("- The gold set is small (").append(items.size()).append(" questions) and written by the author; treat the numbers as directional.\n")
                .append("- Both arms use the same LLM, embeddings, corpus and retrieval, so the difference isolates the self-correction loop.\n");
        return md.toString();
    }

    private static void row(StringBuilder md, String name, Map<String, Object> p, Map<String, Object> s, String key, String meaning) {
        md.append("| ").append(name).append(" | ").append(p.get(key)).append("% | ").append(s.get(key)).append("% | ")
                .append(meaning).append(" |\n");
    }

    private static String cell(List<ItemResult> results, String id, String mode) {
        return results.stream().filter(r -> r.id().equals(id) && r.mode().equals(mode)).findFirst()
                .map(r -> (r.correct() ? "OK " : "X ") + r.outcome().toLowerCase())
                .orElse("-");
    }

    private static String categoryCounts(List<GoldItem> items) {
        Map<String, Long> counts = new TreeMap<>();
        items.forEach(i -> counts.merge(i.category(), 1L, Long::sum));
        StringJoiner j = new StringJoiner(", ");
        counts.forEach((k, v) -> j.add(v + " " + k.toLowerCase()));
        return j.toString();
    }

    private void writeReport(String markdown) {
        try {
            Path path = Path.of(props.eval().outputFile());
            if (path.getParent() != null) Files.createDirectories(path.getParent());
            Files.writeString(path, markdown);
            log.info("Evaluation report written to {}", path.toAbsolutePath());
        } catch (Exception e) {
            log.warn("Could not write evaluation report file: {}", e.getMessage());
        }
    }

    public Optional<EvalRun> latest() {
        return runs.findTopByStatusOrderByStartedAtDesc("DONE");
    }

    public Optional<EvalRun> current() {
        return runs.findTopByOrderByStartedAtDesc();
    }

    private static <T> double pct(List<T> list, Predicate<T> p) {
        if (list.isEmpty()) return 0.0;
        return Math.round(list.stream().filter(p).count() * 1000.0 / list.size()) / 10.0;
    }

    private static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }

    private static long percentile(long[] sorted, double q) {
        if (sorted.length == 0) return 0;
        return sorted[Math.min(sorted.length - 1, (int) Math.ceil(q * sorted.length) - 1)];
    }

    private static String abbreviate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }

    private static String quote(String s) {
        return "\"" + String.valueOf(s).replace("\"", "'") + "\"";
    }
}
