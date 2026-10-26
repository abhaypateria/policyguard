package com.policyguard.service;

import com.policyguard.entity.DocumentStatus;
import com.policyguard.entity.PolicyDocument;
import com.policyguard.repository.PolicyDocumentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;

/**
 * Temporal awareness. Every policy version knows WHEN it was in force:
 *
 *   KYC-SOP  v2023  [2023-04-01 ---- 2024-07-01)
 *   KYC-SOP  v2024                   [2024-07-01 ---- now)
 *
 * The validity windows live in ONE place (the policy_document table). Retrieval first asks
 * "which versions were in force on the as-of date?" and only searches those chunks -
 * so a question "as of March 2023" can never be answered from a 2024 rule.
 */
@Service
public class VersionService {

    private final PolicyDocumentRepository documents;

    public VersionService(PolicyDocumentRepository documents) {
        this.documents = documents;
    }

    /**
     * Rebuild the version chain of a family: sort by effectiveFrom, each version is in force
     * until the next one starts. Works even if an older version is uploaded after a newer one.
     */
    @Transactional
    public void recomputeFamily(String family) {
        List<PolicyDocument> versions =
                documents.findByDocumentFamilyAndStatusNotOrderByEffectiveFromAsc(family, DocumentStatus.FAILED);
        for (int i = 0; i < versions.size(); i++) {
            PolicyDocument current = versions.get(i);
            if (i + 1 < versions.size()) {
                PolicyDocument next = versions.get(i + 1);
                current.setEffectiveTo(next.getEffectiveFrom());
                current.setSupersededBy(next.getId());
            } else {
                current.setEffectiveTo(null);
                current.setSupersededBy(null);
            }
        }
        documents.saveAll(versions);
    }

    /** Document versions that were in force (and indexed) on the given date. */
    public Map<UUID, PolicyDocument> inForce(LocalDate asOf) {
        Map<UUID, PolicyDocument> result = new LinkedHashMap<>();
        for (PolicyDocument d : documents.findInForceAsOf(asOf, DocumentStatus.INDEXED)) {
            result.put(d.getId(), d);
        }
        return result;
    }

    /** Human-readable warnings when an answer relies on a version that has since been replaced. */
    public List<String> supersessionNotices(Collection<PolicyDocument> cited, LocalDate asOf) {
        List<String> notices = new ArrayList<>();
        for (PolicyDocument d : cited) {
            if (d.getEffectiveTo() == null) continue;
            String newer = d.getSupersededBy() == null ? "a newer version"
                    : documents.findById(d.getSupersededBy())
                    .map(n -> "'" + n.getVersionLabel() + "'")
                    .orElse("a newer version");
            notices.add("Rule as of " + asOf + ": '" + d.getTitle() + "' version '" + d.getVersionLabel()
                    + "' was in force from " + d.getEffectiveFrom() + " and was SUPERSEDED on " + d.getEffectiveTo()
                    + " by " + newer + ". The current rule may differ - ask without a past date to see it.");
        }
        return notices;
    }
}
