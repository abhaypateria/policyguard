package com.policyguard.service;

import com.policyguard.dto.GroundingReport;
import com.policyguard.dto.GroundingReport.ClaimCheck;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Post-generation check: does every sentence of the answer actually come from the source it cites?
 *
 * For each sentence we take its key words and measure what fraction appear in the cited passage.
 * >= 50% -> SUPPORTED, below -> WEAK, no citation -> UNCITED. The groundedness score is
 * SUPPORTED / total claims. It is deliberately simple and deterministic (no second LLM call),
 * so it is cheap, explainable, and cannot itself hallucinate.
 */
@Component
public class GroundingVerifier {

    private static final Pattern CITATION = Pattern.compile("\\[(S\\d+)]");
    private static final double SUPPORT_THRESHOLD = 0.5;

    public GroundingReport verify(String answer, Map<String, String> sourceTextByLabel) {
        Map<String, Set<String>> sourceWords = new HashMap<>();
        sourceTextByLabel.forEach((label, text) -> sourceWords.put(label, TextUtils.stemmedSet(text)));

        List<ClaimCheck> checks = new ArrayList<>();
        for (String sentence : splitSentences(answer)) {
            List<String> cites = new ArrayList<>(labelsIn(sentence));
            String claim = CITATION.matcher(sentence).replaceAll("").replaceAll("\\s+", " ").trim();
            Set<String> claimWords = TextUtils.stemmedSet(claim);
            if (claimWords.size() < 3) continue; // headings and connective fragments are not claims

            if (cites.isEmpty()) {
                checks.add(new ClaimCheck(claim, "UNCITED", 0.0, List.of()));
                continue;
            }
            double best = 0;
            for (String label : cites) {
                Set<String> words = sourceWords.get(label);
                if (words == null) continue;
                long hits = claimWords.stream().filter(words::contains).count();
                best = Math.max(best, (double) hits / claimWords.size());
            }
            checks.add(new ClaimCheck(claim, best >= SUPPORT_THRESHOLD ? "SUPPORTED" : "WEAK",
                    Math.round(best * 100) / 100.0, cites));
        }

        int supported = (int) checks.stream().filter(c -> c.status().equals("SUPPORTED")).count();
        int weak = (int) checks.stream().filter(c -> c.status().equals("WEAK")).count();
        int uncited = (int) checks.stream().filter(c -> c.status().equals("UNCITED")).count();
        double score = checks.isEmpty() ? 0.0 : Math.round((double) supported / checks.size() * 100) / 100.0;
        return new GroundingReport(checks.size(), supported, weak, uncited, score, checks);
    }

    public static Set<String> citedLabels(String answer) {
        return labelsIn(answer);
    }

    private static final Pattern MULTI_CITATION = Pattern.compile("\\[(S\\d+(?:\\s*[,;]\\s*S\\d+)+)]");

    /**
     * Models invent citation styles: 【S1】, 【S1†L3】, [S1, S3], (S1). Normalise them all to [S1][S3], and replace
     * typographic spaces/hyphens (narrow no-break space, non-breaking hyphen) with plain ones so "10 days" matches.
     */
    public static String normalize(String text) {
        String t = text.replace(' ', ' ').replace(' ', ' ').replace(' ', ' ')
                .replace('‑', '-').replace('‐', '-');
        t = t.replaceAll("[【［]\\s*(S\\d+)[^】］]*[】］]", "[$1]");
        t = t.replaceAll("\\[\\s*(S\\d+)\\s*]", "[$1]");
        Matcher m = MULTI_CITATION.matcher(t);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            StringBuilder rep = new StringBuilder();
            for (String label : m.group(1).split("\\s*[,;]\\s*")) rep.append('[').append(label.trim()).append(']');
            m.appendReplacement(sb, Matcher.quoteReplacement(rep.toString()));
        }
        m.appendTail(sb);
        t = sb.toString().replaceAll("\\((S\\d+)\\)", "[$1]");
        // Collapse repeats such as "[S1]. [S1]" or "[S1] [S1]" into one citation.
        String previous;
        do {
            previous = t;
            t = t.replaceAll("(\\[S\\d+])\\s*([.!?]?)\\s*\\1", "$1$2");
        } while (!t.equals(previous));
        return t;
    }

    public record Attribution(String text, int added) {}

    /**
     * For factual sentences the model left without a citation, attach the source that clearly contains them
     * (at least 60% of the sentence's key terms). Reported in the trace, never silent.
     */
    public Attribution attributeUncited(String answer, Map<String, String> sourceTextByLabel) {
        Map<String, Set<String>> sourceWords = new LinkedHashMap<>();
        sourceTextByLabel.forEach((label, text) -> sourceWords.put(label, TextUtils.stemmedSet(text)));
        int added = 0;
        String[] lines = answer.split("\\r?\\n", -1);
        StringBuilder out = new StringBuilder();
        for (int li = 0; li < lines.length; li++) {
            List<String> rebuilt = new ArrayList<>();
            for (String s : lines[li].split("(?<=[.!?])\\s+(?=[A-Z(\\[])")) {
                String body = s.replaceFirst("^\\s*([-*•]|\\d+[.)])\\s*", "");
                Set<String> words = TextUtils.stemmedSet(body);
                if (CITATION.matcher(s).find() || words.size() < 3) {
                    rebuilt.add(s);
                    continue;
                }
                String bestLabel = null;
                double best = 0;
                for (Map.Entry<String, Set<String>> e : sourceWords.entrySet()) {
                    double overlap = (double) words.stream().filter(e.getValue()::contains).count() / words.size();
                    if (overlap > best) {
                        best = overlap;
                        bestLabel = e.getKey();
                    }
                }
                if (bestLabel != null && best >= 0.6) {
                    String trimmed = s.stripTrailing();
                    char last = trimmed.isEmpty() ? ' ' : trimmed.charAt(trimmed.length() - 1);
                    rebuilt.add(".!?".indexOf(last) >= 0
                            ? trimmed.substring(0, trimmed.length() - 1) + " [" + bestLabel + "]" + last
                            : trimmed + " [" + bestLabel + "]");
                    added++;
                } else {
                    rebuilt.add(s);
                }
            }
            out.append(String.join(" ", rebuilt));
            if (li < lines.length - 1) out.append('\n');
        }
        return new Attribution(out.toString(), added);
    }

    private static Set<String> labelsIn(String text) {
        Set<String> labels = new LinkedHashSet<>();
        Matcher m = CITATION.matcher(text);
        while (m.find()) labels.add(m.group(1));
        return labels;
    }

    /** Split into sentences, keeping "... 30 days. [S1]" citations attached to the sentence before them. */
    static List<String> splitSentences(String text) {
        String normalised = text.replaceAll("\\.\\s*((\\[S\\d+])+)", " $1.");
        List<String> out = new ArrayList<>();
        for (String line : normalised.split("\\r?\\n")) {
            String l = line.replaceFirst("^\\s*([-*•]|\\d+[.)])\\s*", "").replace("**", "").trim();
            if (l.isEmpty()) continue;
            for (String s : l.split("(?<=[.!?])\\s+(?=[A-Z(\\[])")) {
                if (!s.isBlank()) out.add(s.trim());
            }
        }
        return out;
    }
}
