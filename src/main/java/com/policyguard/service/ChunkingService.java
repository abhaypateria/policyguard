package com.policyguard.service;

import com.policyguard.config.PolicyGuardProperties;
import com.policyguard.entity.PolicyDocument;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Step 2 of ingestion: pages -> small chunks, each tagged with metadata.
 *
 * Why ~200 tokens? The embedding model (all-MiniLM-L6-v2) only reads ~256 word-pieces, so a
 * bigger chunk would be silently truncated before embedding. Smaller chunks also make
 * page-level citations precise. Too small and a rule loses its context - 200 is the balance.
 *
 * Each chunk starts with a one-line header ("title - version"). This "contextual chunk header"
 * lets both search methods tell an RBI rule apart from the bank's internal SOP on the same topic.
 */
@Service
public class ChunkingService {

    private final PolicyGuardProperties props;

    public ChunkingService(PolicyGuardProperties props) {
        this.props = props;
    }

    public List<Document> chunk(List<Document> pages, PolicyDocument doc) {
        TokenTextSplitter splitter = new TokenTextSplitter(
                props.chunking().sizeTokens(),   // target chunk size in tokens
                props.chunking().minChunkChars(), // try to end a chunk on a sentence boundary after this many chars
                40,                               // drop fragments shorter than this
                10_000,                           // safety cap on chunks per page
                true);                            // keep paragraph separators

        String header = doc.getTitle() + " - " + doc.getVersionLabel() + "\n";
        List<Document> chunks = new ArrayList<>();
        for (Document page : pages) {
            int pageNo = pageNumber(page);
            String text = clean(page.getText());
            if (text.length() < 40) continue;

            for (String piece : splitter.apply(List.of(new Document(text))).stream()
                    .flatMap(d -> capLength(d.getText()).stream()).toList()) {
                Map<String, Object> meta = new HashMap<>();
                meta.put("documentId", doc.getId().toString());
                meta.put("title", doc.getTitle());
                meta.put("issuer", doc.getIssuer());
                meta.put("sourceType", doc.getSourceType().name());
                meta.put("documentFamily", doc.getDocumentFamily());
                meta.put("versionLabel", doc.getVersionLabel());
                meta.put("effectiveFrom", doc.getEffectiveFrom().toString());
                meta.put("page", pageNo);
                meta.put("chunkIndex", chunks.size());
                chunks.add(new Document(UUID.randomUUID().toString(), header + piece.trim(), meta));
            }
        }
        return chunks;
    }

    /**
     * Safety net: token counts and character counts can diverge (e.g. tables of numbers), so any chunk
     * longer than ~1,400 characters is split again at whitespace. Keeps every chunk inside the window
     * the embedding model actually reads.
     */
    static List<String> capLength(String text) {
        final int max = 1400;
        List<String> parts = new ArrayList<>();
        String rest = text.trim();
        while (rest.length() > max) {
            int cut = rest.lastIndexOf(' ', max);
            if (cut < max / 2) cut = max;
            parts.add(rest.substring(0, cut).trim());
            rest = rest.substring(cut).trim();
        }
        if (!rest.isEmpty()) parts.add(rest);
        return parts;
    }

    private static int pageNumber(Document page) {
        Object p = page.getMetadata().get("page_number");
        return p instanceof Number n ? n.intValue() : 1;
    }

    /** PDF text comes out with ragged spacing and hyphenated line breaks - normalise it. */
    static String clean(String raw) {
        return raw.replace("­", "")
                .replaceAll("(\\.\\s?){3,}", " ")          // table-of-contents dot leaders
                .replaceAll("(\\w)-\\n(\\w)", "$1$2")
                .replaceAll("[ \\t\\x0B\\f]+", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }
}
