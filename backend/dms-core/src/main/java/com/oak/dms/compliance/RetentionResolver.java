package com.oak.dms.compliance;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Which retention rule applies to a document, and the dates it gives - pure rules with no database, so every
 * combination can be tested directly (CRM-FR-011 and the "conflicting rules" alternate flow).
 *
 * <p>A rule covers a document when its scope matches: the whole organisation, the document type, the owner's
 * department, or the document's folder (or a folder above it, if the rule includes subfolders). Several can cover one
 * document at once; the organisation's chosen {@code precedence} picks the winner so the result is never ambiguous:
 * <ul>
 *   <li>{@code longest} - the rule that keeps the document the longest (the most restrictive for disposal); ties go to
 *       the more specific rule;</li>
 *   <li>{@code most_specific} - folder, then document type, then department, then the organisation default (the nearer
 *       folder wins among folders); ties go to the longer retention.</li>
 * </ul>
 *
 * <p>Dates: the active period starts at the document's creation or last change (the rule's {@code clock_start}) - or at
 * the moment someone last reinstated it by hand, if later, so a reinstated document gets a fresh active period instead of
 * being archived again at once. It ends at {@code archiveDue}; the archive period then runs to {@code reviewDue}, after
 * which an administrator decides. An administrator's extension can only push {@code reviewDue} later.
 */
public final class RetentionResolver {

    public static final String LONGEST = "longest";
    public static final String MOST_SPECIFIC = "most_specific";

    private RetentionResolver() {
    }

    /** What the rules need to know about a document. */
    public record Facts(UUID documentTypeId, UUID ownerDepartmentId, UUID folderId, OffsetDateTime createdAt,
                        OffsetDateTime updatedAt, OffsetDateTime reactivatedAt, OffsetDateTime extendedUntil) {
    }

    /** The winning rule and its dates. */
    public record Resolution(RetentionPolicy policy, OffsetDateTime archiveDue, OffsetDateTime reviewDue) {
    }

    private record Candidate(RetentionPolicy policy, int specificity, Resolution resolution) {
    }

    /**
     * @param folderParents every folder of the organisation mapped to its parent (null for a top-level folder)
     */
    public static Optional<Resolution> resolve(Facts doc, List<RetentionPolicy> policies, String precedence,
                                               Map<UUID, UUID> folderParents) {
        List<UUID> folderChain = folderChain(doc.folderId(), folderParents);
        List<Candidate> candidates = new ArrayList<>();
        for (RetentionPolicy policy : policies) {
            if (!policy.isEnabled() || policy.isDeleted()) {
                continue;
            }
            Integer specificity = specificity(policy, doc, folderChain);
            if (specificity != null) {
                candidates.add(new Candidate(policy, specificity, dates(policy, doc)));
            }
        }
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        Comparator<Candidate> longer = Comparator.comparing(c -> c.resolution().reviewDue());
        // "Greater" = better, because the winner is the maximum.
        Comparator<Candidate> moreSpecific = Comparator.comparingInt(Candidate::specificity);
        Comparator<Candidate> order = MOST_SPECIFIC.equals(precedence)
                ? moreSpecific.thenComparing(longer)
                : longer.thenComparing(moreSpecific);
        return candidates.stream().max(order).map(Candidate::resolution);
    }

    /** null = the rule does not cover this document; otherwise how specific it is (higher = more specific). */
    private static Integer specificity(RetentionPolicy policy, Facts doc, List<UUID> folderChain) {
        return switch (policy.getScopeType()) {
            case RetentionPolicy.SCOPE_ORGANISATION -> 0;
            case RetentionPolicy.SCOPE_DEPARTMENT ->
                    policy.getScopeId() != null && policy.getScopeId().equals(doc.ownerDepartmentId()) ? 100 : null;
            case RetentionPolicy.SCOPE_DOCUMENT_TYPE ->
                    policy.getScopeId() != null && policy.getScopeId().equals(doc.documentTypeId()) ? 200 : null;
            case RetentionPolicy.SCOPE_FOLDER -> {
                int distance = folderChain.indexOf(policy.getScopeId());
                if (distance < 0 || (distance > 0 && !policy.isIncludeSubfolders())) {
                    yield null;
                }
                // The folder the document is in beats its parents, which beat theirs.
                yield 300 + (folderChain.size() - distance);
            }
            default -> null;
        };
    }

    static Resolution dates(RetentionPolicy policy, Facts doc) {
        OffsetDateTime clock = RetentionPolicy.CLOCK_LAST_MODIFIED.equals(policy.getClockStart())
                && doc.updatedAt() != null && doc.updatedAt().isAfter(doc.createdAt())
                ? doc.updatedAt() : doc.createdAt();
        OffsetDateTime start = doc.reactivatedAt() != null && doc.reactivatedAt().isAfter(clock) ? doc.reactivatedAt() : clock;
        OffsetDateTime archiveDue = policy.activeSpan().addTo(start);
        OffsetDateTime reviewDue = policy.archiveSpan().addTo(archiveDue);
        if (doc.extendedUntil() != null && doc.extendedUntil().isAfter(reviewDue)) {
            reviewDue = doc.extendedUntil();
        }
        return new Resolution(policy, archiveDue, reviewDue);
    }

    /** The document's folder, then its parent, and so on up to the top level. Guards against a cycle. */
    static List<UUID> folderChain(UUID folderId, Map<UUID, UUID> parents) {
        List<UUID> chain = new ArrayList<>();
        UUID current = folderId;
        while (current != null && !chain.contains(current)) {
            chain.add(current);
            current = parents.get(current);
        }
        return chain;
    }
}
