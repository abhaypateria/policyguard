package com.policyguard.entity;

import jakarta.persistence.*;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One answered (or refused) question. Records are append-only and hash-chained:
 *     hash(n) = SHA-256( prevHash + all fields of record n ),  where prevHash = hash(n-1)
 * Editing or deleting any past record breaks the chain from that point on,
 * which GET /api/audit/verify detects.
 */
@Entity
@Table(name = "audit_record")
public class AuditRecord {

    @Id
    private Long seq;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(nullable = false)
    private String question;

    @Column(name = "as_of_date")
    private LocalDate asOfDate;

    @Column(nullable = false)
    private String mode;

    @Column(nullable = false)
    private String outcome;

    private String answer;

    private Double confidence;

    @Column(name = "citations_json")
    private String citationsJson;

    @Column(name = "retrieved_chunk_ids")
    private String retrievedChunkIds;

    @Column(name = "document_versions")
    private String documentVersions;

    private Integer attempts;

    @Column(name = "llm_provider")
    private String llmProvider;

    @Column(name = "latency_ms")
    private Long latencyMs;

    @Column(name = "prev_hash", nullable = false)
    private String prevHash;

    @Column(nullable = false)
    private String hash;

    public Long getSeq() { return seq; }
    public void setSeq(Long seq) { this.seq = seq; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }
    public LocalDate getAsOfDate() { return asOfDate; }
    public void setAsOfDate(LocalDate asOfDate) { this.asOfDate = asOfDate; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public String getOutcome() { return outcome; }
    public void setOutcome(String outcome) { this.outcome = outcome; }
    public String getAnswer() { return answer; }
    public void setAnswer(String answer) { this.answer = answer; }
    public Double getConfidence() { return confidence; }
    public void setConfidence(Double confidence) { this.confidence = confidence; }
    public String getCitationsJson() { return citationsJson; }
    public void setCitationsJson(String citationsJson) { this.citationsJson = citationsJson; }
    public String getRetrievedChunkIds() { return retrievedChunkIds; }
    public void setRetrievedChunkIds(String retrievedChunkIds) { this.retrievedChunkIds = retrievedChunkIds; }
    public String getDocumentVersions() { return documentVersions; }
    public void setDocumentVersions(String documentVersions) { this.documentVersions = documentVersions; }
    public Integer getAttempts() { return attempts; }
    public void setAttempts(Integer attempts) { this.attempts = attempts; }
    public String getLlmProvider() { return llmProvider; }
    public void setLlmProvider(String llmProvider) { this.llmProvider = llmProvider; }
    public Long getLatencyMs() { return latencyMs; }
    public void setLatencyMs(Long latencyMs) { this.latencyMs = latencyMs; }
    public String getPrevHash() { return prevHash; }
    public void setPrevHash(String prevHash) { this.prevHash = prevHash; }
    public String getHash() { return hash; }
    public void setHash(String hash) { this.hash = hash; }
}
