package com.oak.dms.importexport.worker;

import com.oak.dms.importexport.repository.ImportItemRepository;
import com.oak.dms.importexport.validation.ImportDefaults;
import com.oak.dms.importexport.validation.ImportSchemaFields;
import com.oak.dms.scanning.service.IngestionGate;
import com.oak.dms.security.auth.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Follow-up for import rows whose scan hadn't finished when the worker
 * submitted them (a cloud scanner, or a scan engine that was down and is
 * being retried). Every {@code dms.import-export.completion-interval-ms}
 * (default 30s) it re-checks the ticket of each such row in a running
 * batch, applies it exactly as the worker would, then tries to finalise
 * the batches it touched.
 *
 * <p>The gate only hands a ticket back to the user who uploaded it, so each
 * row is resolved as its batch's creator. A batch whose creator has been
 * deleted is never run as nobody: it goes to
 * {@link ImportBatchFinaliser#finaliseWithoutCreator} instead.
 *
 * <p>Scheduled the same way as {@code ScanRetryScheduler}. Safe to run on
 * several instances at once: the candidate list isn't locked, but
 * {@link ImportRecordCreator#handleTicket} claims each row with
 * {@code FOR UPDATE SKIP LOCKED} and re-checks that it is still pending,
 * so a row is only ever resolved once, and fetching a ticket twice is
 * harmless.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImportCompletionScheduler {

    static final int BATCH_SIZE = 100;

    private final ImportItemRepository importItemRepository;
    private final ImportSchemaFields importSchemaFields;
    private final ImportDefaults importDefaults;
    private final IngestionGate ingestionGate;
    private final ImportRecordCreator recordCreator;
    private final ImportBatchFinaliser finaliser;

    @Scheduled(fixedDelayString = "${dms.import-export.completion-interval-ms:30000}",
            initialDelayString = "${dms.import-export.completion-interval-ms:30000}")
    public void completePendingItems() {
        List<PendingCompletion> candidates = importItemRepository.findPendingCompletions(PageRequest.of(0, BATCH_SIZE));
        Map<UUID, ImportDefaults.Resolved> defaultsByBatch = new HashMap<>();
        Set<UUID> touched = new LinkedHashSet<>();
        Set<UUID> withoutCreator = new LinkedHashSet<>();

        for (PendingCompletion candidate : candidates) {
            if (candidate.createdBy() == null) {
                withoutCreator.add(candidate.importBatchId());
                continue;
            }
            touched.add(candidate.importBatchId());
            TenantContext.set(candidate.tenantId(), candidate.createdBy());
            try {
                ImportDefaults.Resolved defaults = defaultsByBatch.computeIfAbsent(candidate.importBatchId(),
                        id -> importDefaults.resolve(importSchemaFields.load(candidate.tenantId(),
                                candidate.documentTypeId(), candidate.schemaVersion()), candidate.tenantId(),
                                candidate.documentTypeId(), candidate.schemaVersion()));
                recordCreator.handleTicket(candidate.batch(), candidate.importItemId(),
                        ingestionGate.getTicket(candidate.scanResultId()), defaults);
            } catch (RuntimeException e) {
                // Left pending - tried again on the next pass.
                log.error("Completing import item {} of batch {} failed", candidate.importItemId(),
                        candidate.importBatchId(), e);
            } finally {
                TenantContext.clear();
            }
        }

        for (UUID batchId : withoutCreator) {
            // Its rows waiting on a ticket stay pending (no user can fetch
            // the ticket); everything else in the batch fails.
            log.warn("Creator of bulk import batch {} no longer exists; its scanned rows can't be completed",
                    batchId);
            try {
                finaliser.finaliseWithoutCreator(batchId);
            } catch (RuntimeException e) {
                log.error("Finalising bulk import batch {} without its creator failed", batchId, e);
            }
        }

        for (UUID batchId : touched) {
            try {
                finaliser.finalise(batchId);
            } catch (RuntimeException e) {
                log.error("Finalising bulk import batch {} failed", batchId, e);
            }
        }
    }
}
