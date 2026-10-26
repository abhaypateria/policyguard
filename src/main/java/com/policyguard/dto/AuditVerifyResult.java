package com.policyguard.dto;

/**
 * Result of re-computing the whole hash chain.
 * intact=false means a record was edited or removed; brokenAtSeq says where.
 */
public record AuditVerifyResult(
        boolean intact,
        long checked,
        Long brokenAtSeq,
        String reason,
        Long headSeq,
        String headHash,
        long verifyMs) {
}
