package com.policyguard.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pgvector.PGvector;
import com.policyguard.config.PolicyGuardProperties;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * HYBRID retrieval = two searches merged.
 *
 *  1. Dense  (pgvector): "find passages that MEAN the same thing" - cosine similarity of embeddings.
 *  2. Sparse (Postgres full-text): "find passages containing the same WORDS" - catches acronyms,
 *     form numbers and section references that embeddings blur ("Form 60", "NRE", "V-CIP").
 *
 * The two ranked lists are merged with Reciprocal Rank Fusion:  score = sum( 1 / (60 + rank) ).
 * RRF only uses ranks, so the two very different score scales never need to be normalised.
 *
 * Both searches are restricted to the document versions in force on the as-of date.
 */
@Service
public class RetrievalService {

    /** One retrieved chunk plus how each search ranked it. */
    public record RetrievedChunk(String id, String text, Map<String, Object> metadata, double similarity,
                                 Integer denseRank, Integer sparseRank, double fusedScore) {

        public String documentId() { return String.valueOf(metadata.get("documentId")); }

        public Integer page() {
            Object p = metadata.get("page");
            return p instanceof Number n ? n.intValue() : null;
        }

        /** Chunk text without the one-line contextual header. */
        public String body() {
            int nl = text.indexOf('\n');
            return nl > 0 ? text.substring(nl + 1).trim() : text;
        }
    }

    private static final int RRF_K = 60;

    private final VectorStore vectorStore;
    private final EmbeddingModel embeddingModel;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final PolicyGuardProperties props;

    public RetrievalService(VectorStore vectorStore, EmbeddingModel embeddingModel, JdbcTemplate jdbc,
                            ObjectMapper mapper, PolicyGuardProperties props) {
        this.vectorStore = vectorStore;
        this.embeddingModel = embeddingModel;
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.props = props;
    }

    public String mode() {
        return props.retrieval().hybrid() ? "hybrid (dense + keyword)" : "dense";
    }

    public List<RetrievedChunk> retrieve(String query, Collection<String> documentIds) {
        if (documentIds.isEmpty()) return List.of();
        int candidates = props.retrieval().candidateK();
        Map<String, Acc> merged = new LinkedHashMap<>();

        // ---- 1. Dense search through Spring AI's VectorStore (same API as the reference project) ----
        Filter.Expression inForce = new FilterExpressionBuilder().in("documentId", documentIds.toArray()).build();
        List<Document> dense = vectorStore.similaritySearch(SearchRequest.builder()
                .query(query)
                .topK(candidates)
                .similarityThreshold(SearchRequest.SIMILARITY_THRESHOLD_ACCEPT_ALL)
                .filterExpression(inForce)
                .build());
        for (int i = 0; i < dense.size(); i++) {
            Document d = dense.get(i);
            Map<String, Object> meta = new HashMap<>(d.getMetadata());
            meta.remove("distance");
            Acc a = merged.computeIfAbsent(d.getId(), id -> new Acc(id, d.getText(), meta));
            a.denseRank = i + 1;
            a.similarity = Math.max(a.similarity, d.getScore() == null ? 0.0 : d.getScore());
        }

        // ---- 2. Sparse keyword search (Postgres full-text, GIN index) ----
        if (props.retrieval().hybrid()) {
            String tsQuery = toTsQuery(query);
            if (!tsQuery.isBlank()) {
                for (SparseRow row : keywordSearch(query, tsQuery, documentIds, candidates)) {
                    Acc a = merged.computeIfAbsent(row.id(), id -> new Acc(id, row.content(), row.metadata()));
                    if (a.sparseRank == null) a.sparseRank = row.rank();
                    a.similarity = Math.max(a.similarity, row.similarity());
                }
            }
        }

        // ---- 3. Reciprocal Rank Fusion ----
        return merged.values().stream()
                .map(Acc::toChunk)
                .sorted(Comparator.comparingDouble(RetrievedChunk::fusedScore).reversed())
                .limit(props.retrieval().topK())
                .toList();
    }

    private record SparseRow(String id, String content, Map<String, Object> metadata, double similarity, int rank) {}

    private List<SparseRow> keywordSearch(String query, String tsQuery, Collection<String> documentIds, int limit) {
        float[] queryVector = embeddingModel.embed(query);
        String placeholders = String.join(",", Collections.nCopies(documentIds.size(), "?"));
        String sql = """
                SELECT id::text AS id, content, metadata::text AS metadata,
                       1 - (embedding <=> ?) AS similarity
                FROM vector_store, to_tsquery('english', ?) q
                WHERE to_tsvector('english', content) @@ q
                  AND (metadata ->> 'documentId') IN (%s)
                ORDER BY ts_rank_cd(to_tsvector('english', content), q) DESC
                LIMIT ?
                """.formatted(placeholders);
        List<Object> params = new ArrayList<>();
        params.add(new PGvector(queryVector));
        params.add(tsQuery);
        params.addAll(documentIds);
        params.add(limit);

        List<SparseRow> rows = new ArrayList<>();
        jdbc.query(sql, rs -> {
            rows.add(new SparseRow(rs.getString("id"), rs.getString("content"),
                    parseMetadata(rs.getString("metadata")), rs.getDouble("similarity"), rows.size() + 1));
        }, params.toArray());
        return rows;
    }

    /** "What documents are needed for an NRI account?" -> "documents | nri | account" (OR-query). */
    static String toTsQuery(String question) {
        Set<String> terms = new LinkedHashSet<>();
        for (String w : question.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (w.length() >= 2 && !TextUtils.STOPWORDS.contains(w)) terms.add(w);
        }
        return String.join(" | ", terms);
    }

    private Map<String, Object> parseMetadata(String json) {
        try {
            return mapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    /** Mutable accumulator used while merging the two result lists. */
    private static final class Acc {
        final String id;
        final String text;
        final Map<String, Object> metadata;
        Integer denseRank;
        Integer sparseRank;
        double similarity;

        Acc(String id, String text, Map<String, Object> metadata) {
            this.id = id;
            this.text = text;
            this.metadata = metadata;
        }

        RetrievedChunk toChunk() {
            double fused = (denseRank == null ? 0 : 1.0 / (RRF_K + denseRank))
                    + (sparseRank == null ? 0 : 1.0 / (RRF_K + sparseRank));
            return new RetrievedChunk(id, text, metadata, similarity, denseRank, sparseRank, fused);
        }
    }
}
