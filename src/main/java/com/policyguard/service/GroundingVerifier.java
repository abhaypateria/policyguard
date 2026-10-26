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
