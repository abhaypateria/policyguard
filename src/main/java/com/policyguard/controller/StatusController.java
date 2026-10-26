package com.policyguard.controller;

import com.policyguard.config.PolicyGuardProperties;
import com.policyguard.entity.DocumentStatus;
import com.policyguard.repository.AuditRecordRepository;
import com.policyguard.repository.PolicyDocumentRepository;
import com.policyguard.service.LlmGateway;
import com.policyguard.service.RetrievalService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.Map;

/** One-call system overview for the UI header and for monitoring. */
@RestController
public class StatusController {

    private final PolicyDocumentRepository documents;
    private final AuditRecordRepository audit;
    private final JdbcTemplate jdbc;
    private final LlmGateway llm;
    private final RetrievalService retrieval;
    private final PolicyGuardProperties props;

    public StatusController(PolicyDocumentRepository documents, AuditRecordRepository audit, JdbcTemplate jdbc,
                            LlmGateway llm, RetrievalService retrieval, PolicyGuardProperties props) {
        this.documents = documents;
        this.audit = audit;
        this.jdbc = jdbc;
        this.llm = llm;
        this.retrieval = retrieval;
        this.props = props;
    }

    @GetMapping("/api/status")
    public Map<String, Object> status() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("application", "PolicyGuard");
        s.put("version", "1.0.0");
        s.put("uptimeSeconds", ManagementFactory.getRuntimeMXBean().getUptime() / 1000);
        s.put("embeddingModel", "all-MiniLM-L6-v2 (local ONNX, 384 dimensions)");
        s.put("vectorStore", "PostgreSQL + pgvector (HNSW, cosine)");
        s.put("retrieval", retrieval.mode());
        s.put("topK", props.retrieval().topK());
        s.put("minSimilarity", props.retrieval().minSimilarity());
        s.put("maxAttempts", props.rag().maxAttempts());
        s.put("llmProviders", llm.status());
        s.put("documents", Map.of(
                "total", documents.count(),
                "indexed", documents.countByStatus(DocumentStatus.INDEXED),
                "processing", documents.countByStatus(DocumentStatus.PROCESSING),
                "failed", documents.countByStatus(DocumentStatus.FAILED)));
        s.put("chunks", jdbc.queryForObject("SELECT count(*) FROM vector_store", Long.class));
        s.put("auditRecords", audit.count());
        s.put("adminProtected", !props.security().adminKey().isBlank());
        return s;
    }
}
