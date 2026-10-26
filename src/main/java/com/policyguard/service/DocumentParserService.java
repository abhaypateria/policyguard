package com.policyguard.service;

import com.policyguard.exception.ApiException;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Step 1 of ingestion: raw file -> one Spring AI Document per PAGE.
 * We use PagePdfDocumentReader (page-aware) rather than Tika because every citation
 * must point to an exact page number.
 */
@Service
public class DocumentParserService {

    public List<Document> parse(byte[] bytes, String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".pdf")) {
            PagePdfDocumentReader reader = new PagePdfDocumentReader(new NamedResource(bytes, fileName));
            List<Document> pages = reader.get().stream()
                    .filter(d -> d.getText() != null && !d.getText().isBlank())
                    .toList();
            if (pages.isEmpty()) {
                throw ApiException.badRequest("No extractable text found - is this a scanned PDF? (OCR is not supported yet)");
            }
            return pages;
        }
        if (lower.endsWith(".txt") || lower.endsWith(".md")) {
            return List.of(new Document(new String(bytes, StandardCharsets.UTF_8), Map.of("page_number", 1)));
        }
        throw ApiException.badRequest("Unsupported file type - upload a PDF, TXT or MD file");
    }

    /** PDFBox needs a filename on the resource; a plain ByteArrayResource has none. */
    private static final class NamedResource extends ByteArrayResource {
        private final String name;

        NamedResource(byte[] bytes, String name) {
            super(bytes);
            this.name = name;
        }

        @Override
        public String getFilename() {
            return name;
        }
    }
}
