package com.oak.dms.ocrworker;

import com.oak.dms.document.DocumentMetadataService;
import com.oak.dms.document.DocumentOcrFieldResult;
import com.oak.dms.document.DocumentOcrFieldResultRepository;
import com.oak.dms.document.DocumentVersion;
import com.oak.dms.document.DocumentVersionRepository;
import com.oak.dms.document.OcrFieldResultMapper;
import com.oak.dms.security.auth.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Applies OCR fields for versions the {@code document.version.ocr.completed}
 * event never reached, and says so.
 * <p>
 * This replaces the poll-time re-apply in
 * {@code DocumentServiceImpl#getOcrStatus} (see
 * {@code docs/ocr/phase1-closeout.md} section 5). Both exist for the same
 * failure -- a lost {@code .completed} event leaves
 * {@code document_ocr_field_results} correct and
 * {@code document_metadata_values} silently empty, with no path to recovery
 * short of a manual OCR re-run -- but they differ in the way that matters:
 * <p>
 * The poll-time re-apply only recovered a document somebody happened to
 * open, and it recovered it <em>silently</em>. That is exactly how it masked
 * a total failure of the event path for an entire phase: the
 * {@code OutboxRelay} double-encoding defect killed every {@code .completed}
 * delivery, and nobody noticed because the UI kept showing correct metadata
 * (see {@code docs/ocr/phase1-verification.md}, "Defects found" #1). This
 * reconciler finds every affected document whether or not anyone looks at
 * it, and <strong>logs a warning for each one</strong>. The warning is the
 * point: a healthy system produces none, and each one that appears is a
 * message that genuinely went missing.
 * <p>
 * <strong>Concurrency.</strong> No lock and no ShedLock. The marker stamp is
 * a guarded compare-and-swap
 * ({@code markOcrFieldsAppliedIfUnset}), so if two instances -- or this
 * reconciler and the {@code .completed} listener -- reach the same version
 * at once, exactly one gets a row back and logs. The other applies the same
 * idempotent mapping and stays quiet. That is the same approach ocr-worker's
 * reaper takes ({@code reaper.py}: "every write is the same guarded CAS
 * UPDATE {@code claim_job} itself uses, so at most one instance wins each
 * row with no additional locking primitive").
 * <p>
 * <strong>Tenancy.</strong> There is no ambient tenant on a scheduler
 * thread, and no row-level security to fall back on -- isolation in this
 * system is entirely application-level. The scan is therefore deliberately
 * tenant-agnostic, and each row's own {@code tenant_id} is bound into
 * {@link TenantContext} before {@code applyOcrFields} and cleared in a
 * {@code finally}. The clear is not optional: the scheduler thread is
 * long-lived and pooled, so a leaked binding would apply one tenant's
 * context to the next row.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OcrFieldsReconciler {

    /** The seeded system actor (V24), same constant and same reasoning as {@link OcrEventsConsumer}. */
    private static final UUID SYSTEM_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final DocumentVersionRepository documentVersionRepository;
    private final DocumentOcrFieldResultRepository documentOcrFieldResultRepository;
    private final DocumentMetadataService documentMetadataService;
    private final OcrProperties ocrProperties;

    @Scheduled(fixedDelayString = "${dms.ocr.reconciler-interval-ms:60000}")
    public void reconcile() {
        if (!ocrProperties.isReconcilerEnabled()) {
            return;
        }
        try {
            reconcileOnce();
        } catch (Exception e) {
            // A scheduled method that throws is simply not re-run by some
            // schedulers and logs a bare stack trace in others. Swallowing
            // here keeps the tick alive; per-version failures are already
            // contained below, so reaching this means the scan itself
            // failed (a DB outage), which the next tick will retry.
            log.error("OCR fields reconciler tick failed: {}", e.getMessage(), e);
        }
    }

    /**
     * One pass. Package-private and separate from the {@code @Scheduled}
     * wrapper so a unit test can drive it directly -- nothing in this
     * codebase has ever had a test that fires a scheduled method, and the
     * same split is already used for {@code DocumentServiceImpl}'s
     * package-private static helpers.
     *
     * @return how many versions this pass actually claimed and applied.
     */
    int reconcileOnce() {
        OffsetDateTime cutoff = OffsetDateTime.now()
                .minus(Duration.ofMinutes(ocrProperties.getStaleAfterMinutes()));

        List<DocumentVersion> stale = documentVersionRepository.findCompletedWithUnappliedOcrFields(
                cutoff, PageRequest.of(0, ocrProperties.getBatchSize()));
        if (stale.isEmpty()) {
            return 0;
        }

        int applied = 0;
        for (DocumentVersion version : stale) {
            if (reconcileOne(version, cutoff)) {
                applied++;
            }
        }
        return applied;
    }

    private boolean reconcileOne(DocumentVersion version, OffsetDateTime cutoff) {
        UUID versionId = version.getVersionId();
        try {
            // Claim first, then apply. Claiming first means a concurrent
            // instance cannot also log about this version; and because
            // applyOcrFields is idempotent, a crash between the claim and
            // the apply costs a re-run of a mapping that converges to the
            // same state, which is much cheaper than two warnings and two
            // writers racing.
            OffsetDateTime now = OffsetDateTime.now();
            if (documentVersionRepository.markOcrFieldsAppliedIfUnset(versionId, now) == 0) {
                return false;  // another instance, or the listener, got there first
            }

            int fieldCount;
            TenantContext.set(version.getTenantId(), SYSTEM_USER_ID);
            try {
                List<DocumentOcrFieldResult> rows = documentOcrFieldResultRepository
                        .findByDocumentIdAndTenantId(version.getDocumentId(), version.getTenantId());
                Map<String, Object> ocrFieldsRaw = OcrFieldResultMapper.toOcrFieldsRaw(rows);
                fieldCount = ocrFieldsRaw.size();
                if (!ocrFieldsRaw.isEmpty()) {
                    documentMetadataService.applyOcrFields(version.getDocumentId(), ocrFieldsRaw);
                }
            } finally {
                TenantContext.clear();
            }

            log.warn(
                    "Reconciled OCR fields for version {} (document {}, tenant {}): OCR completed before {} "
                            + "but its fields were never applied -- the document.version.ocr.completed event "
                            + "did not arrive. Applied {} field(s) now.",
                    versionId, version.getDocumentId(), version.getTenantId(), cutoff, fieldCount);
            return true;
        } catch (Exception e) {
            // One bad document must not stop the batch. applyOcrFields is
            // REQUIRES_NEW, so its own rollback is already contained (see
            // DocumentMetadataServiceImpl's comment on the propagation
            // choice) -- this catch is for everything around it.
            log.error("Could not reconcile OCR fields for version {}: {}", versionId, e.getMessage(), e);
            return false;
        }
    }
}
