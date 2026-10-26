package com.policyguard.entity;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/** Stored result of one evaluation run (plain RAG vs self-correcting RAG on the gold question set). */
@Entity
@Table(name = "eval_run")
public class EvalRun {

    @Id
    private UUID id;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(nullable = false)
    private String status;

    private String provider;

    @Column(name = "summary_json")
    private String summaryJson;

    @Column(name = "details_json")
    private String detailsJson;

    private String markdown;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getSummaryJson() { return summaryJson; }
    public void setSummaryJson(String summaryJson) { this.summaryJson = summaryJson; }
    public String getDetailsJson() { return detailsJson; }
    public void setDetailsJson(String detailsJson) { this.detailsJson = detailsJson; }
    public String getMarkdown() { return markdown; }
    public void setMarkdown(String markdown) { this.markdown = markdown; }
}
