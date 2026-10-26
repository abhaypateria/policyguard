package com.policyguard.service;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.policyguard.dto.Conflict;

import java.util.ArrayList;
import java.util.List;

/**
 * The evidence grader's verdict, parsed from the LLM's JSON reply.
 * Parsing is deliberately lenient (LLMs add code fences, single quotes, trailing commas).
 * If the reply cannot be parsed at all we "fail open" to the next safety layers - the
 * similarity threshold and the answer prompt's own INSUFFICIENT_EVIDENCE abstention -
 * rather than refusing every question because of a formatting glitch.
 */
public record Grade(boolean parsed, boolean relevant, boolean sufficient, String missing,
                    String rewrittenQuery, List<Conflict> conflicts) {

    private static final ObjectMapper LENIENT = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_SINGLE_QUOTES)
            .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
            .enable(JsonReadFeature.ALLOW_UNQUOTED_FIELD_NAMES)
            .build();

    public static Grade parse(String raw) {
        try {
            String text = raw.replace("```json", "").replace("```", "");
            int start = text.indexOf('{');
            int end = text.lastIndexOf('}');
            if (start < 0 || end <= start) throw new IllegalArgumentException("no JSON object in reply");
            JsonNode n = LENIENT.readTree(text.substring(start, end + 1));

            List<Conflict> conflicts = new ArrayList<>();
            for (JsonNode c : n.path("conflicts")) {
                if (c.isTextual()) {
                    // Some models return plain sentences instead of objects: keep them, pull out any [S#] labels.
                    String description = c.asText().trim();
                    if (description.isEmpty()) continue;
                    List<String> labels = new ArrayList<>();
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\bS(\\d+)\\b").matcher(description);
                    while (m.find()) labels.add("S" + m.group(1));
                    conflicts.add(new Conflict(labels, description));
                    continue;
                }
                List<String> sources = new ArrayList<>();
                for (JsonNode s : c.path("sources")) {
                    sources.add(s.asText().replaceAll("[^A-Za-z0-9]", "").toUpperCase());
                }
                String description = c.path("description").asText("");
                if (!description.isBlank()) conflicts.add(new Conflict(sources, description));
            }
            boolean relevant = n.path("relevant").asBoolean(true);
            boolean sufficient = n.path("sufficient").asBoolean(false);
            // Business rule enforced in code, not left to the model: a conflict between relevant sources means
            // the answer IS in the corpus (in two versions). That is a finding to surface, never a reason to refuse.
            // (Small models often mark conflicting evidence "insufficient" even when told not to.)
            if (relevant && !conflicts.isEmpty()) sufficient = true;
            return new Grade(true, relevant, sufficient,
                    n.path("missing").asText(""),
                    n.path("rewritten_query").asText(""),
                    conflicts);
        } catch (Exception e) {
            return new Grade(false, true, true, "", "", List.of());
        }
    }
}
