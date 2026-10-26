package com.policyguard.controller;

import com.policyguard.dto.DocumentDto;
import com.policyguard.dto.UploadMetadata;
import com.policyguard.entity.PolicyDocument;
import com.policyguard.entity.SourceType;
import com.policyguard.exception.ApiException;
import com.policyguard.repository.DocumentFileRepository;
import com.policyguard.repository.PolicyDocumentRepository;
import com.policyguard.service.IndexingService;
import com.policyguard.service.IngestionService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Upload, list, open and delete policy documents. Upload returns 202: indexing continues in the background. */
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final IngestionService ingestion;
    private final IndexingService indexing;
    private final PolicyDocumentRepository documents;
    private final DocumentFileRepository files;

    public DocumentController(IngestionService ingestion, IndexingService indexing,
                              PolicyDocumentRepository documents, DocumentFileRepository files) {
        this.ingestion = ingestion;
        this.indexing = indexing;
        this.documents = documents;
        this.files = files;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentDto> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam String title,
            @RequestParam String issuer,
            @RequestParam SourceType sourceType,
            @RequestParam String documentFamily,
            @RequestParam String versionLabel,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate effectiveFrom,
            @RequestHeader(value = "X-User", required = false) String user) throws IOException {
        PolicyDocument saved = ingestion.register(file.getBytes(), file.getOriginalFilename(), file.getContentType(),
                new UploadMetadata(title, issuer, sourceType, documentFamily, versionLabel, effectiveFrom), user);
        indexing.indexAsync(saved.getId()); // starts AFTER the registration transaction committed
        return ResponseEntity.accepted().body(DocumentDto.from(saved));
    }

    @GetMapping
    public List<DocumentDto> list() {
        return documents.findAllByOrderByDocumentFamilyAscEffectiveFromAsc().stream().map(DocumentDto::from).toList();
    }

    @GetMapping("/{id}")
    public DocumentDto get(@PathVariable UUID id) {
        return DocumentDto.from(find(id));
    }

    /** Streams the original file inline, so the UI can open a citation at "#page=N". */
    @GetMapping("/{id}/file")
    public ResponseEntity<byte[]> file(@PathVariable UUID id) {
        PolicyDocument d = find(id);
        byte[] bytes = files.findById(id).orElseThrow(() -> ApiException.notFound("File bytes not found")).getBytes();
        MediaType type = d.getFileName().toLowerCase().endsWith(".pdf") ? MediaType.APPLICATION_PDF : MediaType.TEXT_PLAIN;
        return ResponseEntity.ok()
                .contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + d.getFileName().replace("\"", "") + "\"")
                .body(bytes);
    }

    @PostMapping("/{id}/reindex")
    public ResponseEntity<Map<String, String>> reindex(@PathVariable UUID id) {
        find(id);
        indexing.indexAsync(id);
        return ResponseEntity.accepted().body(Map.of("status", "re-indexing started"));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        ingestion.delete(id);
        return ResponseEntity.noContent().build();
    }

    private PolicyDocument find(UUID id) {
        return documents.findById(id).orElseThrow(() -> ApiException.notFound("Document not found: " + id));
    }
}
