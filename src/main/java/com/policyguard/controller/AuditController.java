package com.policyguard.controller;

import com.policyguard.dto.AuditVerifyResult;
import com.policyguard.entity.AuditRecord;
import com.policyguard.exception.ApiException;
import com.policyguard.repository.AuditRecordRepository;
import com.policyguard.service.AuditService;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/** Read the audit trail and prove it has not been tampered with. */
@RestController
@RequestMapping("/api/audit")
public class AuditController {

    private final AuditService audit;
    private final AuditRecordRepository records;

    public AuditController(AuditService audit, AuditRecordRepository records) {
        this.audit = audit;
        this.records = records;
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "25") int size) {
        Page<AuditRecord> p = audit.list(page, size);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("content", p.getContent());
        body.put("page", p.getNumber());
        body.put("size", p.getSize());
        body.put("totalElements", p.getTotalElements());
        body.put("totalPages", p.getTotalPages());
        return body;
    }

    @GetMapping("/{seq}")
    public AuditRecord get(@PathVariable long seq) {
        return records.findById(seq).orElseThrow(() -> ApiException.notFound("Audit record #" + seq + " not found"));
    }

    /** Recompute the entire hash chain. */
    @GetMapping("/verify")
    public AuditVerifyResult verify() {
        return audit.verify();
    }

    /** The current head of the chain - publish this externally to make tail-truncation detectable. */
    @GetMapping("/head")
    public Map<String, Object> head() {
        Map<String, Object> body = new LinkedHashMap<>();
        audit.head().ifPresentOrElse(h -> {
            body.put("seq", h.getSeq());
            body.put("hash", h.getHash());
            body.put("createdAt", h.getCreatedAt());
        }, () -> {
            body.put("seq", 0);
            body.put("hash", AuditService.GENESIS);
        });
        return body;
    }
}
