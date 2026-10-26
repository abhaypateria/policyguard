package com.policyguard.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.policyguard.dto.AuditVerifyResult;
import com.policyguard.dto.ChatResponse;
import com.policyguard.dto.Citation;
import com.policyguard.entity.AuditRecord;
import com.policyguard.repository.AuditRecordRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * TAMPER-EVIDENT AUDIT LOG (a hash chain, the same idea that links blocks in a blockchain).
 *
 *   record 1: hash1 = SHA-256( GENESIS + fields1 )
 *   record 2: hash2 = SHA-256( hash1   + fields2 )
 *   record 3: hash3 = SHA-256( hash2   + fields3 )
 *
 * Change one character of record 2 and its recomputed hash no longer matches; record 3's
 * prev_hash then points at a hash that does not exist. Delete record 2 and the sequence has a gap.
 * verify() walks the chain and reports the first broken record.
 *
 * Limitation (stated honestly): deleting the newest records ("tail truncation") leaves a valid
 * shorter chain. The fix is anchoring: periodically publishing the head hash outside the database
 * (GET /api/audit/head), so the true length can always be proven.
 */
@Service
public class AuditService {

    public static final String GENESIS = "0".repeat(64);
    private static final char SEP = '\u001F'; // ASCII unit separator: cannot be confused with field content

    private final AuditRecordRepository records;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;
    private final ReentrantLock appendLock = new ReentrantLock();

    public AuditService(AuditRecordRepository records, TransactionTemplate tx, ObjectMapper mapper) {
        this.records = records;
        this.tx = tx;
        this.mapper = mapper;
    }

    /** Write the response to the audit log and return it with the audit seq + hash attached. */
    public ChatResponse seal(ChatResponse response, String userId) {
        AuditRecord r = new AuditRecord();
        r.setUserId(userId);
        r.setQuestion(response.question());
        r.setAsOfDate(response.asOfDate());
        r.setMode(response.mode().name());
        r.setOutcome(response.outcome().name());
        r.setAnswer(response.answer());
        r.setConfidence(response.confidence());
        r.setCitationsJson(json(response.sources().stream().filter(Citation::cited)
                .map(c -> Map.of("label", c.label(), "documentId", c.documentId(), "title", c.title(),
                        "version", c.versionLabel(), "page", c.page() == null ? 0 : c.page()))
                .toList()));
        r.setRetrievedChunkIds(json(response.sources().stream().map(Citation::chunkId).toList()));
        r.setDocumentVersions(json(response.sources().stream()
                .map(c -> c.documentFamily() + "@" + c.versionLabel()).distinct().toList()));
        r.setAttempts(response.attempts());
        r.setLlmProvider(response.provider());
        r.setLatencyMs(response.latencyMs());
        AuditRecord saved = append(r);
        return response.withAudit(saved.getSeq(), saved.getHash());
    }

    /**
     * Appends under a lock, and the lock is held until the transaction COMMITS, so two concurrent
     * requests can never both read the same "last record" and fork the chain.
     */
    public AuditRecord append(AuditRecord r) {
        appendLock.lock();
        try {
            return tx.execute(status -> {
                Optional<AuditRecord> last = records.findTopByOrderBySeqDesc();
                r.setSeq(last.map(a -> a.getSeq() + 1).orElse(1L));
                r.setPrevHash(last.map(AuditRecord::getHash).orElse(GENESIS));
                r.setCreatedAt(Instant.now().truncatedTo(ChronoUnit.MILLIS)); // DB stores micros; keep it exact
                if (r.getConfidence() != null) r.setConfidence(Math.round(r.getConfidence() * 10000) / 10000.0);
                r.setHash(computeHash(r));
                return records.save(r);
            });
        } finally {
            appendLock.unlock();
        }
    }

    /** Recompute every hash from the first record to the last. */
    public AuditVerifyResult verify() {
        long start = System.currentTimeMillis();
        String expectedPrev = GENESIS;
        long expectedSeq = 1;
        long checked = 0;
        AuditRecord last = null;
        int pageNo = 0;
        Page<AuditRecord> page;
        do {
            page = records.findAllByOrderBySeqAsc(PageRequest.of(pageNo++, 500));
            for (AuditRecord r : page.getContent()) {
                long seq = r.getSeq();
                if (seq != expectedSeq) {
                    return broken(checked, seq, "record #" + expectedSeq + " is MISSING (deleted) - the sequence jumps to #" + seq, start);
                }
                if (!r.getPrevHash().equals(expectedPrev)) {
                    return broken(checked, seq, "chain link broken: prev_hash of #" + seq + " does not match the hash of #" + (seq - 1), start);
                }
                String recomputed = computeHash(r);
                if (!recomputed.equals(r.getHash())) {
                    return broken(checked, seq, "record #" + seq + " was MODIFIED after it was written: stored hash "
                            + r.getHash().substring(0, 12) + "... but its contents now hash to " + recomputed.substring(0, 12) + "...", start);
                }
                expectedPrev = r.getHash();
                expectedSeq++;
                checked++;
                last = r;
            }
        } while (page.hasNext());
        return new AuditVerifyResult(true, checked, null, "All " + checked + " records verified - chain intact",
                last == null ? null : last.getSeq(), last == null ? GENESIS : last.getHash(), System.currentTimeMillis() - start);
    }

    public Page<AuditRecord> list(int page, int size) {
        return records.findAllByOrderBySeqDesc(PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100)));
    }

    public Optional<AuditRecord> head() {
        return records.findTopByOrderBySeqDesc();
    }

    public static String computeHash(AuditRecord r) {
        return Hashing.sha256(canonical(r));
    }

    /** Deterministic string of every field, in a fixed order. Any change to any field changes the hash. */
    static String canonical(AuditRecord r) {
        return String.join(String.valueOf(SEP),
                str(r.getSeq()), str(r.getCreatedAt()), str(r.getUserId()), str(r.getQuestion()), str(r.getAsOfDate()),
                str(r.getMode()), str(r.getOutcome()), str(r.getAnswer()),
                r.getConfidence() == null ? "" : String.format(Locale.ROOT, "%.4f", r.getConfidence()),
                str(r.getCitationsJson()), str(r.getRetrievedChunkIds()), str(r.getDocumentVersions()),
                str(r.getAttempts()), str(r.getLlmProvider()), str(r.getLatencyMs()), str(r.getPrevHash()));
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    private AuditVerifyResult broken(long checked, long seq, String reason, long start) {
        return new AuditVerifyResult(false, checked, seq, reason, null, null, System.currentTimeMillis() - start);
    }

    private String json(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            return "[]";
        }
    }
}
