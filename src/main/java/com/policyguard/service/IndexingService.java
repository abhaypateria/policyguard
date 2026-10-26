package com.policyguard.service;

import com.policyguard.entity.DocumentStatus;
import com.policyguard.entity.PolicyDocument;
import com.policyguard.repository.DocumentFileRepository;
import com.policyguard.repository.PolicyDocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The ingestion pipeline (runs in the background):
 *   PDF bytes -> pages (parser) -> chunks + metadata (chunker) -> embeddings + store (VectorStore)
 * VectorStore.add() calls the EmbeddingModel for every chunk and writes vector + metadata to pgvector.
 */
@Service
public class IndexingService {

    private static final Logger log = LoggerFactory.getLogger(IndexingService.class);
    private static final int BATCH_SIZE = 64;

    private final PolicyDocumentRepository documents;
    private final DocumentFileRepository files;
    private final DocumentParserService parser;
    private final ChunkingService chunker;
    private final VectorStore vectorStore;
    private final JdbcTemplate jdbc;
    private final VersionService versions;
    private final ResponseCache cache;

    public IndexingService(PolicyDocumentRepository documents, DocumentFileRepository files, DocumentParserService parser,
                           ChunkingService chunker, VectorStore vectorStore, JdbcTemplate jdbc, VersionService versions,
                           ResponseCache cache) {
        this.documents = documents;
        this.files = files;
        this.parser = parser;
        this.chunker = chunker;
        this.vectorStore = vectorStore;
        this.jdbc = jdbc;
        this.versions = versions;
        this.cache = cache;
    }

    @Async("indexingExecutor")
    public void indexAsync(UUID documentId) {
        index(documentId);
    }

    public void index(UUID documentId) {
        PolicyDocument doc = documents.findById(documentId).orElse(null);
        if (doc == null) return;
        long start = System.currentTimeMillis();
        try {
            byte[] bytes = files.findById(documentId)
                    .orElseThrow(() -> new IllegalStateException("file bytes missing")).getBytes();
            List<Document> pages = parser.parse(bytes, doc.getFileName());
            List<Document> chunks = chunker.chunk(pages, doc);
            if (chunks.isEmpty()) throw new IllegalStateException("no text chunks could be produced");

            deleteChunks(documentId); // makes re-indexing idempotent
            for (int i = 0; i < chunks.size(); i += BATCH_SIZE) {
                vectorStore.add(chunks.subList(i, Math.min(i + BATCH_SIZE, chunks.size())));
            }
            doc.setStatus(DocumentStatus.INDEXED);
            doc.setPageCount(pages.size());
            doc.setChunkCount(chunks.size());
            doc.setIndexedAt(Instant.now());
            doc.setErrorMessage(null);
            log.info("Indexed '{}' ({}): {} pages -> {} chunks in {} ms", doc.getTitle(), doc.getVersionLabel(),
                    pages.size(), chunks.size(), System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.error("Indexing failed for {}", documentId, e);
            doc.setStatus(DocumentStatus.FAILED);
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            doc.setErrorMessage(msg.length() > 900 ? msg.substring(0, 900) : msg);
        }
        documents.save(doc);
        if (doc.getStatus() == DocumentStatus.FAILED) {
            versions.recomputeFamily(doc.getDocumentFamily()); // a failed version must not break the chain
        }
        cache.invalidateAll();
    }

    public int deleteChunks(UUID documentId) {
        return jdbc.update("DELETE FROM vector_store WHERE metadata ->> 'documentId' = ?", documentId.toString());
    }
}
