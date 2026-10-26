package com.policyguard.entity;

import jakarta.persistence.*;

import java.util.UUID;

/** The original uploaded bytes, so a citation can open the exact PDF page. */
@Entity
@Table(name = "document_file")
public class DocumentFile {

    @Id
    @Column(name = "document_id")
    private UUID documentId;

    @Column(nullable = false)
    private byte[] bytes;

    protected DocumentFile() {}

    public DocumentFile(UUID documentId, byte[] bytes) {
        this.documentId = documentId;
        this.bytes = bytes;
    }

    public UUID getDocumentId() { return documentId; }
    public byte[] getBytes() { return bytes; }
}
