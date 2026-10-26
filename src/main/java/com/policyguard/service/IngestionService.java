package com.policyguard.service;

import com.policyguard.dto.UploadMetadata;
import com.policyguard.entity.DocumentFile;
import com.policyguard.entity.DocumentStatus;
import com.policyguard.entity.PolicyDocument;
import com.policyguard.exception.ApiException;
import com.policyguard.repository.DocumentFileRepository;
import com.policyguard.repository.PolicyDocumentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Registers an upload: validates it, fingerprints it (SHA-256), stores the bytes and metadata,
 * and slots it into its version chain. Actual indexing is started by the controller AFTER
 * this transaction commits, so the background worker always sees the saved row.
 */
@Service
public class IngestionService {

    private final PolicyDocumentRepository documents;
    private final DocumentFileRepository files;
    private final VersionService versions;
    private final IndexingService indexing;
    private final ResponseCache cache;

    public IngestionService(PolicyDocumentRepository documents, DocumentFileRepository files, VersionService versions,
                            IndexingService indexing, ResponseCache cache) {
        this.documents = documents;
        this.files = files;
        this.versions = versions;
        this.indexing = indexing;
        this.cache = cache;
    }

    @Transactional
    public PolicyDocument register(byte[] bytes, String fileName, String contentType, UploadMetadata meta, String uploadedBy) {
        if (bytes == null || bytes.length == 0) throw ApiException.badRequest("The uploaded file is empty");
        if (fileName == null || fileName.isBlank()) throw ApiException.badRequest("File name is missing");
        require(meta.title(), "title");
        require(meta.issuer(), "issuer");
        require(meta.documentFamily(), "documentFamily");
        require(meta.versionLabel(), "versionLabel");
        if (meta.sourceType() == null) throw ApiException.badRequest("sourceType is required (REGULATOR or INTERNAL)");
        if (meta.effectiveFrom() == null) throw ApiException.badRequest("effectiveFrom date is required");

        String sha256 = Hashing.sha256(bytes);
        documents.findBySha256(sha256).ifPresent(existing -> {
            throw ApiException.conflict("This exact file is already stored as '" + existing.getTitle()
                    + "' (" + existing.getVersionLabel() + ")");
        });

        PolicyDocument d = new PolicyDocument();
        d.setId(UUID.randomUUID());
        d.setTitle(meta.title().trim());
        d.setFileName(fileName);
        d.setContentType(contentType);
        d.setIssuer(meta.issuer().trim());
        d.setSourceType(meta.sourceType());
        d.setDocumentFamily(meta.documentFamily().trim().toUpperCase());
        d.setVersionLabel(meta.versionLabel().trim());
        d.setEffectiveFrom(meta.effectiveFrom());
        d.setStatus(DocumentStatus.PROCESSING);
        d.setSha256(sha256);
        d.setSizeBytes((long) bytes.length);
        d.setUploadedBy(uploadedBy == null || uploadedBy.isBlank() ? "admin" : uploadedBy);
        d.setUploadedAt(Instant.now());
        documents.save(d);
        files.save(new DocumentFile(d.getId(), bytes));
        versions.recomputeFamily(d.getDocumentFamily());
        return d;
    }

    @Transactional
    public void delete(UUID id) {
        PolicyDocument d = documents.findById(id).orElseThrow(() -> ApiException.notFound("Document not found: " + id));
        indexing.deleteChunks(id);
        files.deleteById(id);
        documents.delete(d);
        documents.flush();
        versions.recomputeFamily(d.getDocumentFamily());
        cache.invalidateAll();
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) throw ApiException.badRequest(field + " is required");
    }
}
