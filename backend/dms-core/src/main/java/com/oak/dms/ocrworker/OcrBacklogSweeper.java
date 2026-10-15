package com.oak.dms.ocrworker;

import com.oak.dms.document.DocumentVersion;
import com.oak.dms.document.DocumentVersionRepository;
import com.oak.dms.security.auth.TenantContext;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

/**
 * Queues the OCR work that was asked for but held back.
 *
 * <h2>What it drains</h2>
 * {@link OcrEnqueueService} declines rather than throws when it won't queue -
 * a bulk import of 5,000 files must not fail because OCR is busy, and the
 * person who uploaded one document must not wait behind that import. When it
 * declines, the version is left with {@code ocr_status = 'pending'} and its
 * {@code requested_fields} intact, and no live job row.
 * <p>
 * That pair <em>is</em> the durable marker. It is a row in the database, not a
 * queue entry and not something in memory, so it survives a restart, a crash
 * and a RabbitMQ outage. This sweeper is what turns it back into work.
 *
 * <h2>Why a sweep and not a retry</h2>
 * The alternative - having the enqueue path retry until it succeeds - would
 * hold a transaction open against a condition it cannot influence. The
 * condition here is "the tenant's queue has drained", which only the workers
 * can change, so the honest shape is to put the request down and look again
 * later.
 *
 * <h2>Tenant context</h2>
 * Tenant-agnostic by design: this is a background job with no ambient tenant,
 * and each row's own {@code tenant_id} is bound per version - the same pattern
 * {@code OcrFieldsReconciler} uses. Nothing here reads {@code TenantContext}
 * without setting it first.
 *
 * <h2>Why {@code queueOne} uses a TransactionTemplate, not {@code @Transactional}</h2>
 * {@code queueOne} used to be {@code @Transactional(REQUIRES_NEW)}, called as
 * {@code queueOne(version)} from {@link #sweep()} on the same bean - a plain
 * Java call that never goes through the Spring AOP proxy, so the annotation
 * was silently never applied. {@link OcrEnqueueService#enqueue} requires an
 * existing transaction ({@code MANDATORY}) and found none, threw on every
 * single version, and the {@code catch} below swallowed it and counted the
 * version as queued anyway - so this sweeper reported success while queueing
 * nothing, for every tenant, from the day it was written. Same trap
 * {@code WatermarkOutputSweeper#purge} documents and avoids the same way:
 * a {@link TransactionTemplate} built from the injected
 * {@link PlatformTransactionManager} actually starts a transaction no matter
 * who calls the method.
 */
@Slf4j
@Component
public class OcrBacklogSweeper {

    private final DocumentVersionRepository documentVersionRepository;
    private final OcrEnqueueService ocrEnqueueService;
    private final OcrProperties properties;
    private final TransactionTemplate newTransaction;

    public OcrBacklogSweeper(DocumentVersionRepository documentVersionRepository,
                             OcrEnqueueService ocrEnqueueService,
                             OcrProperties properties,
                             PlatformTransactionManager transactionManager) {
        this.documentVersionRepository = documentVersionRepository;
        this.ocrEnqueueService = ocrEnqueueService;
        this.properties = properties;
        this.newTransaction = new TransactionTemplate(transactionManager);
        // REQUIRES_NEW: one held-back version whose document has since been
        // purged must not roll back the rest of the batch - see queueOne.
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Scheduled(fixedDelayString = "${dms.ocr.backlog-sweep-interval-ms:30000}")
    public void sweep() {
        if (!properties.isEnabled()) {
            return;
        }
        List<DocumentVersion> held = documentVersionRepository.findOcrHeldBack(
                PageRequest.of(0, properties.getBacklogSweepBatchSize()));
        if (held.isEmpty()) {
            return;
        }
        int queued = 0;
        for (DocumentVersion version : held) {
            // Each in its own transaction: one version whose document has
            // since been purged must not roll back the rest of the batch.
            if (queueOne(version)) {
                queued++;
            } else {
                // The queue is still full. Stop rather than walk the rest of
                // the batch only to be declined for each - they will still be
                // here next tick, which is the whole point of the marker.
                break;
            }
        }
        log.info("OCR backlog sweep: {} of {} held-back versions queued", queued, held.size());
    }

    private boolean queueOne(DocumentVersion version) {
        UUID tenantId = version.getTenantId();
        TenantContext.set(tenantId, null);
        try {
            // Interactive, not bulk_import: by the time a held-back version is
            // swept, whatever batch it came from is long finished, and the
            // low-priority queue exists to stop an import starving live work -
            // not to punish the import's files forever.
            return Boolean.TRUE.equals(newTransaction.execute(status ->
                    ocrEnqueueService.enqueue(tenantId, version.getDocumentId(), version.getVersionId(),
                            null, OcrEnqueueService.Source.interactive)));
        } catch (RuntimeException e) {
            log.warn("Could not queue held-back OCR for version {}: {}", version.getVersionId(), e.toString());
            // Treated as "keep going": a single bad row (a purged document, a
            // missing storage location) must not stall the whole backlog.
            return true;
        } finally {
            TenantContext.clear();
        }
    }
}
