-- =====================================================================
-- V1: PolicyGuard schema. Applied once by Flyway, recorded in flyway_schema_history.
-- =====================================================================
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS hstore;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- One row per uploaded policy file. Versions of the same policy share a document_family;
-- effective_from/effective_to define the period during which that version was in force.
CREATE TABLE policy_document (
    id              UUID PRIMARY KEY,
    title           VARCHAR(300) NOT NULL,
    file_name       VARCHAR(300) NOT NULL,
    content_type    VARCHAR(100),
    issuer          VARCHAR(150) NOT NULL,
    source_type     VARCHAR(20)  NOT NULL,
    document_family VARCHAR(150) NOT NULL,
    version_label   VARCHAR(100) NOT NULL,
    effective_from  DATE         NOT NULL,
    effective_to    DATE,
    superseded_by   UUID,
    status          VARCHAR(20)  NOT NULL,
    page_count      INT,
    chunk_count     INT,
    sha256          VARCHAR(64)  NOT NULL UNIQUE,
    size_bytes      BIGINT,
    error_message   TEXT,
    uploaded_by     VARCHAR(100),
    uploaded_at     TIMESTAMPTZ  NOT NULL,
    indexed_at      TIMESTAMPTZ
);
CREATE INDEX idx_policy_document_family ON policy_document (document_family, effective_from);

-- Original file bytes (kept separate so listing documents never loads PDFs into memory).
CREATE TABLE document_file (
    document_id UUID PRIMARY KEY REFERENCES policy_document (id) ON DELETE CASCADE,
    bytes       BYTEA NOT NULL
);

-- Append-only, hash-chained audit log: hash = SHA-256(prev_hash + record fields).
CREATE TABLE audit_record (
    seq                 BIGINT PRIMARY KEY,
    created_at          TIMESTAMPTZ NOT NULL,
    user_id             VARCHAR(100) NOT NULL,
    question            TEXT NOT NULL,
    as_of_date          DATE,
    mode                VARCHAR(30) NOT NULL,
    outcome             VARCHAR(30) NOT NULL,
    answer              TEXT,
    confidence          DOUBLE PRECISION,
    citations_json      TEXT,
    retrieved_chunk_ids TEXT,
    document_versions   TEXT,
    attempts            INT,
    llm_provider        VARCHAR(80),
    latency_ms          BIGINT,
    prev_hash           VARCHAR(64) NOT NULL,
    hash                VARCHAR(64) NOT NULL
);

-- Stored results of evaluation runs (plain RAG vs self-correcting RAG).
CREATE TABLE eval_run (
    id           UUID PRIMARY KEY,
    started_at   TIMESTAMPTZ NOT NULL,
    finished_at  TIMESTAMPTZ,
    status       VARCHAR(20) NOT NULL,
    provider     VARCHAR(80),
    summary_json TEXT,
    details_json TEXT,
    markdown     TEXT
);

-- Spring AI PgVectorStore table (same layout Spring AI would create), 384-dim MiniLM vectors.
CREATE TABLE vector_store (
    id        UUID DEFAULT uuid_generate_v4() PRIMARY KEY,
    content   TEXT,
    metadata  JSON,
    embedding VECTOR(384)
);
-- HNSW: approximate nearest-neighbour index for fast cosine-similarity search.
CREATE INDEX vector_store_embedding_hnsw ON vector_store USING HNSW (embedding vector_cosine_ops);
-- GIN full-text index: powers the keyword half of hybrid search (acronyms, section numbers).
CREATE INDEX vector_store_content_fts ON vector_store USING GIN (to_tsvector('english', content));
-- Lets us delete / filter a document's chunks quickly.
CREATE INDEX vector_store_document_id ON vector_store ((metadata ->> 'documentId'));
