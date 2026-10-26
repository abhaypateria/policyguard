package com.policyguard;

import com.policyguard.config.PolicyGuardProperties;
import com.policyguard.dto.GroundingReport;
import com.policyguard.entity.AuditRecord;
import com.policyguard.entity.PolicyDocument;
import com.policyguard.entity.SourceType;
import com.policyguard.service.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformers.TransformersEmbeddingModel;
import org.springframework.core.io.ClassPathResource;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Database-free tests of the pipeline's building blocks, run against the REAL RBI corpus:
 * PDF parsing with page numbers, chunk sizes, local embeddings, the grounding verifier,
 * the grader's lenient JSON parsing, and the audit hash chain's tamper detection.
 */
class PipelineTest {

    private static List<Document> pages;
    private static PolicyDocument doc;
    private static final PolicyGuardProperties PROPS = new PolicyGuardProperties(
            new PolicyGuardProperties.Retrieval(6, 15, true, 0.30),
            new PolicyGuardProperties.Rag(3),
            new PolicyGuardProperties.Chunking(200, 150),
            new PolicyGuardProperties.Llm(List.of(), 5, 90),
            new PolicyGuardProperties.Security("", 20, 1000, "*"),
            new PolicyGuardProperties.Cache(true, 30),
            new PolicyGuardProperties.Seed(false),
            new PolicyGuardProperties.Eval(false, "target/EVALUATION.md"));

    @BeforeAll
    static void parseCorpus() throws Exception {
        byte[] bytes = new ClassPathResource("corpus/rbi-kyc-master-direction.pdf").getInputStream().readAllBytes();
        pages = new DocumentParserService().parse(bytes, "rbi-kyc-master-direction.pdf");
        doc = new PolicyDocument();
        doc.setId(UUID.randomUUID());
        doc.setTitle("Master Direction - KYC");
        doc.setIssuer("Reserve Bank of India");
        doc.setSourceType(SourceType.REGULATOR);
        doc.setDocumentFamily("RBI-KYC-MD");
        doc.setVersionLabel("Updated as on 14-Aug-2025");
        doc.setEffectiveFrom(LocalDate.of(2025, 8, 14));
    }

    @Test
    void parserKeepsRealPageNumbers() {
        assertTrue(pages.size() > 100, "107-page PDF should yield ~107 page documents");
        Document page39 = pages.stream()
                .filter(p -> ((Number) p.getMetadata().get("page_number")).intValue() == 39)
                .findFirst().orElseThrow();
        assertTrue(page39.getText().replaceAll("\\s+", " ").contains("once in every two years"),
                "periodic-updation rule must be on page 39, so citations point to the right page");
    }

    @Test
    void chunksAreSmallEnoughForTheEmbeddingModel() {
        List<Document> chunks = new ChunkingService(PROPS).chunk(pages, doc);
        assertTrue(chunks.size() > 150, "expected a few hundred chunks, got " + chunks.size());
        for (Document c : chunks) {
            assertNotNull(c.getMetadata().get("page"));
            assertEquals(doc.getId().toString(), c.getMetadata().get("documentId"));
            // header line + at most 1,400 characters of body: stays inside MiniLM's reading window
            assertTrue(c.getText().length() < 1_550, "chunk too long: " + c.getText().length());
        }
    }

    @Test
    void localEmbeddingsCaptureMeaning() throws Exception {
        TransformersEmbeddingModel model = new TransformersEmbeddingModel();
        model.setTokenizerOptions(Map.of("maxLength", "256", "truncation", "true", "padding", "true"));
        model.setResourceCacheDirectory(System.getProperty("java.io.tmpdir") + "/policyguard-onnx");
        model.afterPropertiesSet();

        float[] query = model.embed("how often must KYC be refreshed for low risk customers");
        float[] relevant = model.embed("periodic updation shall be carried out once in every ten years for low-risk customers");
        float[] unrelated = model.embed("utility bill which is not more than two months old of any service provider");
        assertEquals(384, query.length, "all-MiniLM-L6-v2 produces 384-dimension vectors");
        assertTrue(cosine(query, relevant) > cosine(query, unrelated),
                "the semantically related passage must score higher than the unrelated one");
    }

    @Test
    void groundingVerifierFlagsUnsupportedClaims() {
        Map<String, String> sources = Map.of("S1",
                "periodic updation shall be carried out at least once in every two years for high-risk customers");
        GroundingReport r = new GroundingVerifier().verify(
                "High-risk customers must have KYC periodically updated at least once every two years [S1]. "
                        + "Customers also receive a free credit card upgrade every year.", sources);
        assertEquals(2, r.claims());
        assertEquals(1, r.supported());
        assertEquals(1, r.uncited());
    }

    @Test
    void graderParsesMessyJsonAndFailsOpenOnGarbage() {
        Grade g = Grade.parse("```json\n{'relevant': true, 'sufficient': false, 'missing': 'NRO rules', "
                + "'rewritten_query': 'NRO account documents', 'conflicts': [{'sources': ['[S1]','S3'], 'description': '15 vs 10 days'}],}\n```");
        assertTrue(g.parsed());
        assertTrue(g.sufficient(), "relevant sources that CONFLICT still answer the question (rule enforced in code)");
        assertEquals("NRO account documents", g.rewrittenQuery());
        assertEquals(List.of("S1", "S3"), g.conflicts().get(0).sources());

        Grade insufficient = Grade.parse("{\"relevant\": true, \"sufficient\": false, \"missing\": \"NRO documents\", \"conflicts\": []}");
        assertFalse(insufficient.sufficient(), "no conflict and no answer -> stays insufficient");

        Grade prose = Grade.parse("{\"relevant\": true, \"sufficient\": false, \"conflicts\": [\"S1 says 10 days while S2 says 15 days\"]}");
        assertEquals(List.of("S1", "S2"), prose.conflicts().get(0).sources(), "labels are extracted from prose conflicts");

        Grade garbage = Grade.parse("I think the sources look fine!");
        assertFalse(garbage.parsed());
        assertTrue(garbage.sufficient(), "unparseable grader output must not block every answer");
    }

    @Test
    void citationStylesAndTypographyAreNormalised() {
        assertEquals("Within 10 days [S1].", GroundingVerifier.normalize("Within 10 days 【S1】."));
        assertEquals("Both rules [S1][S3].", GroundingVerifier.normalize("Both rules [S1, S3]."));
        assertEquals("PAN or Form 60 [S2]", GroundingVerifier.normalize("PAN or Form 60 (S2)"));
        assertEquals("Rs. 10,000 [S1].", GroundingVerifier.normalize("Rs. 10,000 [S1]. [S1]"));
        assertEquals("against cash [S1] and more", GroundingVerifier.normalize("against cash [ S1 ] [S1] and more"));
    }

    @Test
    void uncitedSentencesAreAttributedOnlyWhenTheSourceClearlyContainsThem() {
        Map<String, String> sources = Map.of("S2",
                "a valid visa, work permit or residence permit of the country of residence; proof of overseas address");
        GroundingVerifier.Attribution a = new GroundingVerifier().attributeUncited(
                "- Valid visa, work permit or residence permit of the country of residence\n- A free airport lounge pass", sources);
        assertEquals(1, a.added(), "only the sentence the source supports gets a citation");
        assertTrue(a.text().startsWith("- Valid visa, work permit or residence permit of the country of residence [S2]"));
        assertTrue(a.text().endsWith("- A free airport lounge pass"));
    }

    @Test
    void groqRetryAfterHintIsRespected() {
        assertEquals(8_160, LlmGateway.retryAfterMs("Rate limit reached... Please try again in 7.66s. Need more tokens?"));
        assertEquals(62_500, LlmGateway.retryAfterMs("Please try again in 1m2s."));
        assertEquals(1_020, LlmGateway.retryAfterMs("Please try again in 520ms."));
        assertEquals(30_000, LlmGateway.retryAfterMs("429 Too Many Requests"));
    }

    @Test
    void auditHashChangesWhenAnyFieldIsTampered() {
        AuditRecord r = new AuditRecord();
        r.setSeq(7L);
        r.setCreatedAt(Instant.parse("2026-10-26T10:15:30.123Z"));
        r.setUserId("branch-officer-01");
        r.setQuestion("What documents are needed to open an NRE account?");
        r.setMode("SELF_CORRECTING");
        r.setOutcome("ANSWERED");
        r.setAnswer("A valid passport and a visa [S1].");
        r.setConfidence(0.87);
        r.setPrevHash(AuditService.GENESIS);
        String original = AuditService.computeHash(r);
        assertEquals(original, AuditService.computeHash(r), "hashing must be deterministic");

        r.setAnswer("A valid passport and a visa [S1]. ");  // a single trailing space
        assertNotEquals(original, AuditService.computeHash(r), "any edit must change the hash");
    }

    private static double cosine(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}
