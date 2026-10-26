package com.policyguard.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.policyguard.config.PolicyGuardProperties;
import com.policyguard.dto.UploadMetadata;
import com.policyguard.entity.PolicyDocument;
import com.policyguard.entity.SourceType;
import com.policyguard.repository.PolicyDocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.time.LocalDate;
import java.util.List;

/**
 * On an EMPTY database, loads the bundled demo corpus (listed in corpus/manifest.json):
 * public RBI directions plus two versions of a clearly-fictional "Demo Bank" internal SOP.
 * A fresh deployment is therefore usable immediately and the evaluation is reproducible.
 */
@Component
public class CorpusSeeder {

    private static final Logger log = LoggerFactory.getLogger(CorpusSeeder.class);

    record Entry(String file, String title, String issuer, SourceType sourceType, String documentFamily,
                 String versionLabel, LocalDate effectiveFrom) {}

    private final PolicyGuardProperties props;
    private final PolicyDocumentRepository documents;
    private final IngestionService ingestion;
    private final IndexingService indexing;
    private final ObjectMapper mapper;

    public CorpusSeeder(PolicyGuardProperties props, PolicyDocumentRepository documents, IngestionService ingestion,
                        IndexingService indexing, ObjectMapper mapper) {
        this.props = props;
        this.documents = documents;
        this.ingestion = ingestion;
        this.indexing = indexing;
        this.mapper = mapper;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seed() {
        if (!props.seed().enabled()) return;
        if (documents.count() > 0) {
            log.info("Corpus already present ({} documents) - skipping seed", documents.count());
            return;
        }
        ClassPathResource manifest = new ClassPathResource("corpus/manifest.json");
        if (!manifest.exists()) return;
        try (InputStream in = manifest.getInputStream()) {
            List<Entry> entries = mapper.readValue(in, new TypeReference<>() {});
            for (Entry e : entries) {
                byte[] bytes;
                try (InputStream fileIn = new ClassPathResource("corpus/" + e.file()).getInputStream()) {
                    bytes = fileIn.readAllBytes();
                }
                PolicyDocument d = ingestion.register(bytes, e.file(), "application/pdf",
                        new UploadMetadata(e.title(), e.issuer(), e.sourceType(), e.documentFamily(),
                                e.versionLabel(), e.effectiveFrom()), "seed");
                indexing.indexAsync(d.getId());
                log.info("Seeded '{}' ({}) - indexing in background", e.title(), e.versionLabel());
            }
        } catch (Exception ex) {
            log.error("Corpus seeding failed", ex);
        }
    }
}
