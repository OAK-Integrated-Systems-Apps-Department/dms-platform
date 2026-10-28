package com.oak.dms.document;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentVersionRepository extends JpaRepository<DocumentVersion, UUID> {

    /**
     * Versions whose OCR completed but whose fields were never applied to
     * metadata, older than a cutoff -- the reconciler's scan.
     * <p>
     * Native SQL rather than a derived query or JPQL because it needs a
     * correlated EXISTS across {@code processing_jobs} and
     * {@code document_ocr_field_results}, neither of which has a JPA
     * association to {@link DocumentVersion}.
     * <p>
     * Three conditions, each load-bearing:
     * <ul>
     * <li>{@code ocr_status = 'completed'} -- the raw status, deliberately
     * not the collapsed one {@code getOcrStatus} reports, because
     * {@code not_required} collapses to "completed" for the UI and has no
     * OCR results to apply.</li>
     * <li>{@code finished_at < cutoff} on the newest OCR job -- the only
     * real completion clock in the schema. {@code document_versions} has no
     * OCR-completion timestamp of its own, and {@code created_at} is the
     * upload time, which says nothing about when OCR finished.</li>
     * <li>at least one applicable field result exists -- a version whose
     * every field came back {@code not_found} (or {@code not_extractable},
     * which is also valueless) has genuinely nothing to apply, and
     * flagging it would produce a warning nobody can act on. The
     * predicate matches {@code OcrFieldResultMapper}'s own skip rule: the
     * {@code value IS NOT NULL} half is what covers
     * {@code not_extractable} in both places.</li>
     * </ul>
     * Tenant-agnostic on purpose: this is a background job with no ambient
     * tenant, and the caller binds {@code TenantContext} per row from the
     * row's own {@code tenant_id}.
     */
    @Query(value = """
            SELECT v.* FROM document_versions v
            WHERE v.ocr_status = 'completed'
              AND v.ocr_fields_applied_at IS NULL
              AND v.is_deleted = false
              AND EXISTS (
                  SELECT 1 FROM processing_jobs j
                  WHERE j.reference_type = 'document_versions'
                    AND j.reference_id = v.version_id
                    AND j.job_type = 'ocr'
                    AND j.finished_at IS NOT NULL
                    AND j.finished_at < :cutoff
              )
              AND EXISTS (
                  SELECT 1 FROM document_ocr_field_results r
                  WHERE r.version_id = v.version_id
                    AND r.value IS NOT NULL
                    AND r.status <> 'not_found'
              )
            ORDER BY v.created_at
            """, nativeQuery = true)
    List<DocumentVersion> findCompletedWithUnappliedOcrFields(
            @Param("cutoff") OffsetDateTime cutoff, Pageable pageable);

    /**
     * Stamps the applied marker, but only if it is still unset.
     * <p>
     * A guarded compare-and-swap, not a plain setter: it is how two dms-core
     * instances (or the reconciler racing the {@code .completed} listener)
     * settle who owns a version without a lock. The winner gets 1 back and
     * logs; every loser gets 0 and stays quiet. This mirrors ocr-worker's
     * own concurrency approach, where every reaper write is a guarded CAS
     * UPDATE rather than a locking primitive (see {@code reaper.py}).
     * <p>
     * Returns the number of rows updated: 1 if this caller claimed it, 0 if
     * someone else already had.
     */
    // REQUIRES_NEW, for the same reason DocumentMetadataServiceImpl#applyOcrFields
    // uses it: the callers have incompatible transactions. getOcrStatus is
    // @Transactional(readOnly = true), which a write joining it would fail
    // against; the RabbitMQ listener has no transaction at all, which a
    // @Modifying query requires. A separate physical transaction satisfies
    // both and makes the CAS commit independently of whatever the caller does
    // next -- which is what makes it a usable claim.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query(value = """
            UPDATE document_versions
            SET ocr_fields_applied_at = :appliedAt
            WHERE version_id = :versionId
              AND ocr_fields_applied_at IS NULL
            """, nativeQuery = true)
    int markOcrFieldsAppliedIfUnset(
            @Param("versionId") UUID versionId, @Param("appliedAt") OffsetDateTime appliedAt);

    /**
     * Unconditional stamp, for a path that has just re-applied fields and
     * wants the marker to reflect that regardless of its previous value --
     * the poll-time re-apply and a deliberate OCR re-run.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query(value = """
            UPDATE document_versions
            SET ocr_fields_applied_at = :appliedAt
            WHERE version_id = :versionId
            """, nativeQuery = true)
    int markOcrFieldsApplied(
            @Param("versionId") UUID versionId, @Param("appliedAt") OffsetDateTime appliedAt);

    /**
     * The review queue (Phase 4b, production prompt 3.13): every version
     * in this tenant that failed OCR or needs a human to look at it.
     *
     * Native SQL so it can use V41's partial index verbatim -- the index
     * covers exactly {@code is_deleted = false AND (requires_review OR
     * ocr_status = 'failed')}, and a JPQL predicate that drifts from that
     * shape silently stops using it and starts scanning every version in
     * the tenant.
     *
     * Only the LATEST version of each document: a queue listing three
     * superseded versions of the same invoice is noise, and the reviewer
     * can only act on the current one anyway.
     */
    @Query(value = """
            SELECT v.* FROM document_versions v
            JOIN documents d ON d.document_id = v.document_id
                            AND d.latest_version_id = v.version_id
            WHERE v.tenant_id = :tenantId
              AND v.is_deleted = false
              AND (v.requires_review = true OR v.ocr_status = 'failed')
            ORDER BY v.created_at DESC
            """,
            countQuery = """
            SELECT count(*) FROM document_versions v
            JOIN documents d ON d.document_id = v.document_id
                            AND d.latest_version_id = v.version_id
            WHERE v.tenant_id = :tenantId
              AND v.is_deleted = false
              AND (v.requires_review = true OR v.ocr_status = 'failed')
            """,
            nativeQuery = true)
    Page<DocumentVersion> findReviewQueue(@Param("tenantId") UUID tenantId, Pageable pageable);

    /**
     * The review queue as {@link #findReviewQueue}, restricted to documents the
     * caller can actually see.
     * <p>
     * The access predicate is applied IN the query rather than by filtering the
     * page afterwards. Filtering after paging would be the obvious shortcut and
     * is wrong twice over: the page would come back short, and the total count
     * would describe documents the caller can't see - which is itself a
     * disclosure ("there are 14 flagged invoices you're not allowed to know
     * about"). Same shape as {@code DocumentRepository.findVisible}: a document
     * is visible when it follows its folder and that folder isn't hidden, or it
     * is restricted and the caller is on its list.
     * <p>
     * {@code hidden} and {@code granted} must never be empty - see
     * {@code DocumentServiceImpl#nonEmpty}.
     */
    @Query(value = """
            SELECT v.* FROM document_versions v
            JOIN documents d ON d.document_id = v.document_id
                            AND d.latest_version_id = v.version_id
            WHERE v.tenant_id = :tenantId
              AND v.is_deleted = false
              AND (v.requires_review = true OR v.ocr_status = 'failed')
              AND ((d.access_mode = 'inherit'
                        AND (d.folder_id IS NULL OR d.folder_id NOT IN (:hidden)))
                   OR (d.access_mode = 'restricted' AND d.document_id IN (:granted)))
            ORDER BY v.created_at DESC
            """,
            countQuery = """
            SELECT count(*) FROM document_versions v
            JOIN documents d ON d.document_id = v.document_id
                            AND d.latest_version_id = v.version_id
            WHERE v.tenant_id = :tenantId
              AND v.is_deleted = false
              AND (v.requires_review = true OR v.ocr_status = 'failed')
              AND ((d.access_mode = 'inherit'
                        AND (d.folder_id IS NULL OR d.folder_id NOT IN (:hidden)))
                   OR (d.access_mode = 'restricted' AND d.document_id IN (:granted)))
            """,
            nativeQuery = true)
    Page<DocumentVersion> findReviewQueueVisible(@Param("tenantId") UUID tenantId,
                                                 @Param("hidden") Collection<UUID> hiddenFolderIds,
                                                 @Param("granted") Collection<UUID> grantedDocumentIds,
                                                 Pageable pageable);

    /**
     * "Saved reviews": latest versions a human has already settled - at least
     * one {@code document_ocr_corrections} row exists for them.
     * <p>
     * Deliberately not "requires_review = false": a version that never needed
     * review has nothing to show on this screen, and one still in the queue
     * may already have some fields saved. The correction rows are the record
     * that somebody actually made a decision.
     */
    @Query(value = """
            SELECT v.* FROM document_versions v
            JOIN documents d ON d.document_id = v.document_id
                            AND d.latest_version_id = v.version_id
            WHERE v.tenant_id = :tenantId
              AND v.is_deleted = false
              AND EXISTS (SELECT 1 FROM document_ocr_corrections c
                          WHERE c.version_id = v.version_id)
              AND ((d.access_mode = 'inherit'
                        AND (d.folder_id IS NULL OR d.folder_id NOT IN (:hidden)))
                   OR (d.access_mode = 'restricted' AND d.document_id IN (:granted)))
            ORDER BY v.created_at DESC
            """,
            countQuery = """
            SELECT count(*) FROM document_versions v
            JOIN documents d ON d.document_id = v.document_id
                            AND d.latest_version_id = v.version_id
            WHERE v.tenant_id = :tenantId
              AND v.is_deleted = false
              AND EXISTS (SELECT 1 FROM document_ocr_corrections c
                          WHERE c.version_id = v.version_id)
              AND ((d.access_mode = 'inherit'
                        AND (d.folder_id IS NULL OR d.folder_id NOT IN (:hidden)))
                   OR (d.access_mode = 'restricted' AND d.document_id IN (:granted)))
            """,
            nativeQuery = true)
    Page<DocumentVersion> findSavedReviewsVisible(@Param("tenantId") UUID tenantId,
                                                  @Param("hidden") Collection<UUID> hiddenFolderIds,
                                                  @Param("granted") Collection<UUID> grantedDocumentIds,
                                                  Pageable pageable);

    /** Saved reviews for a caller who sees everything - no access predicate to pay for. */
    @Query(value = """
            SELECT v.* FROM document_versions v
            JOIN documents d ON d.document_id = v.document_id
                            AND d.latest_version_id = v.version_id
            WHERE v.tenant_id = :tenantId
              AND v.is_deleted = false
              AND EXISTS (SELECT 1 FROM document_ocr_corrections c
                          WHERE c.version_id = v.version_id)
            ORDER BY v.created_at DESC
            """,
            countQuery = """
            SELECT count(*) FROM document_versions v
            JOIN documents d ON d.document_id = v.document_id
                            AND d.latest_version_id = v.version_id
            WHERE v.tenant_id = :tenantId
              AND v.is_deleted = false
              AND EXISTS (SELECT 1 FROM document_ocr_corrections c
                          WHERE c.version_id = v.version_id)
            """,
            nativeQuery = true)
    Page<DocumentVersion> findSavedReviews(@Param("tenantId") UUID tenantId, Pageable pageable);

    /**
     * Versions whose OCR was requested but never queued - held back by the
     * bulk-import batch cap or by per-tenant backpressure. The durable marker
     * is the row itself: {@code ocr_status = 'pending'} with no live
     * processing_jobs row. See {@code OcrBacklogSweeper}.
     */
    @Query(value = """
            SELECT v.* FROM document_versions v
            WHERE v.ocr_status = 'pending'
              AND v.is_deleted = false
              AND v.requested_fields <> '[]'::jsonb
              AND NOT EXISTS (
                  SELECT 1 FROM processing_jobs j
                  WHERE j.reference_type = 'document_versions'
                    AND j.reference_id = v.version_id
                    AND j.job_type = 'ocr'
                    AND j.status IN ('queued', 'processing')
              )
            ORDER BY v.created_at
            """, nativeQuery = true)
    List<DocumentVersion> findOcrHeldBack(Pageable pageable);
    Optional<DocumentVersion> findByVersionIdAndDocumentIdAndTenantIdAndDeletedFalse(
            UUID versionId, UUID documentId, UUID tenantId);

    Optional<DocumentVersion> findTopByDocumentIdOrderByVersionNumberDesc(UUID documentId);

    /** A document's version history, newest first. */
    java.util.List<DocumentVersion> findByDocumentIdAndTenantIdAndDeletedFalseOrderByVersionNumberDesc(UUID documentId,
                                                                                                    UUID tenantId);

    /** Every version, deleted or not - what a permanent delete destroys. */
    java.util.List<DocumentVersion> findByDocumentIdAndTenantId(UUID documentId, UUID tenantId);
}
