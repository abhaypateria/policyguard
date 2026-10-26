package com.policyguard.service;

import java.util.*;

/** Tiny text helpers shared by keyword search and the grounding verifier. */
public final class TextUtils {

    private TextUtils() {}

    public static final Set<String> STOPWORDS = Set.of(
            "a", "an", "the", "and", "or", "of", "to", "in", "on", "for", "by", "with", "as", "at", "from", "is", "are",
            "was", "were", "be", "been", "being", "it", "its", "this", "that", "these", "those", "what", "which", "who",
            "whom", "how", "when", "where", "why", "do", "does", "did", "can", "could", "should", "would", "will", "shall",
            "may", "might", "must", "has", "have", "had", "not", "no", "any", "all", "such", "than", "then", "there",
            "their", "they", "them", "we", "our", "you", "your", "i", "me", "my", "if", "into", "about", "under", "per",
            "also", "other", "each", "s", "required", "need", "needed", "please", "tell", "explain");

    /** Lower-cased content words (length >= 3, not a stopword). */
    public static List<String> contentWords(String text) {
        List<String> words = new ArrayList<>();
        for (String w : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (w.length() >= 3 && !STOPWORDS.contains(w)) words.add(w);
        }
        return words;
    }

    /** Very light stemming so "accounts"/"account" and "documents"/"document" match. */
    public static String stem(String w) {
        if (w.length() > 5 && w.endsWith("ies")) return w.substring(0, w.length() - 3) + "y";
        if (w.length() > 4 && w.endsWith("s") && !w.endsWith("ss")) return w.substring(0, w.length() - 1);
        return w;
    }

    public static Set<String> stemmedSet(String text) {
        Set<String> s = new HashSet<>();
        for (String w : contentWords(text)) s.add(stem(w));
        return s;
    }
}
