package com.oak.dms.importexport.storage;

import com.oak.dms.importexport.repository.ImportItemRepository;
import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.importexport.exception.ImportFileBlockedException;
import com.oak.dms.scanning.dto.ScanTarget;
import com.oak.dms.scanning.dto.ScanVerdict;
import com.oak.dms.scanning.entity.ScanEngine;
import com.oak.dms.scanning.enums.ScanMode;
import com.oak.dms.scanning.enums.ScanVerdictStatus;
import com.oak.dms.scanning.exception.ScanException;
import com.oak.dms.scanning.service.ScanEngineFactory;
import com.oak.dms.scanning.service.ScanEngineResolver;
import com.oak.dms.scanning.service.ScanService;
import com.oak.dms.storage.entity.StorageLocation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Scans a bulk import file the moment it is staged - before it counts as
 * received - so an infected file is refused while the user is still
 * uploading, not discovered only after the batch is submitted. Uses the
 * tenant's own scan engine, the same as the ingestion gate.
 * <ul>
 *   <li>ClamAV (synchronous) gives a verdict now: infected or unscannable
 *   files are refused with {@link ImportFileBlockedException} and the
 *   caller's transaction rolls back, which deletes the staged object.</li>
 *   <li>Cloud scanners (Defender, GuardDuty) only answer after the fact, so
 *   nothing is decided here; their verdict comes at record creation.</li>
 *   <li>If the engine is unreachable the file is accepted as before - it is
 *   still scanned by the ingestion gate before it can become a document.</li>
 * </ul>
 * This is an early check, not the only one: every file goes through the
 * ingestion gate again when its document is created (signatures may have
 * been updated in between), so nothing unscanned is ever promoted.
 */
@Slf4j
@Component
public class IntakeVirusScanner {

    private final ScanEngineResolver scanEngineResolver;
    private final ScanEngineFactory scanEngineFactory;
    private final AuditService auditService;
    private final TransactionTemplate newTransaction;
    private final ImportItemRepository importItemRepository;

    public IntakeVirusScanner(ScanEngineResolver scanEngineResolver, ScanEngineFactory scanEngineFactory,
                              AuditService auditService, PlatformTransactionManager transactionManager,
                              ImportItemRepository importItemRepository) {
        this.scanEngineResolver = scanEngineResolver;
        this.scanEngineFactory = scanEngineFactory;
        this.auditService = auditService;
        this.importItemRepository = importItemRepository;
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * @throws ImportFileBlockedException if the engine finds a threat or can't scan the file
     */
    public void scan(UUID tenantId, UUID batchId, UUID itemId, String filename, StorageLocation location,
                     String objectKey, long sizeBytes, String mimeType) {
        ScanVerdict verdict;
        try {
            ScanEngine engine = scanEngineResolver.resolveForTenant(tenantId);
            ScanService scanner = scanEngineFactory.resolveCompatible(engine, location);
            if (scanner.scanMode() == ScanMode.post_store_object) {
                return; // cloud verdicts arrive later - checked at record creation
            }
            verdict = scanner.scan(engine, new ScanTarget(itemId, location, objectKey, sizeBytes, mimeType, null, null));
        } catch (ScanException e) {
            log.warn("Intake scan of '{}' (bulk import {}) skipped - engine unavailable: {}. "
                    + "It is scanned again before it becomes a document.", filename, batchId, e.getMessage());
            return;
        }
        if (verdict.status() == ScanVerdictStatus.INFECTED) {
            blocked(tenantId, batchId, itemId, filename, verdict, "infected",
                    "The uploaded file was blocked by the virus scan (" + verdict.threatName()
                            + ") - upload a clean copy");
            throw new ImportFileBlockedException("'" + filename + "' was blocked by the virus scan ("
                    + verdict.threatName() + ") and has not been stored.");
        }
        if (verdict.status() == ScanVerdictStatus.UNSCANNABLE) {
            blocked(tenantId, batchId, itemId, filename, verdict, "unscannable",
                    "The uploaded file can't be scanned for viruses (it is encrypted or password-protected) - "
                            + "remove the password and upload it again");
            throw new ImportFileBlockedException("'" + filename + "' can't be scanned for viruses (it is encrypted or "
                    + "password-protected). Remove the password and upload it again.");
        }
    }

    /**
     * Audited - and the reason kept on the row (V63), so its results say a virus was found rather
     * than "no file" - in a transaction of its own: the upload's own transaction is about to roll back.
     */
    private void blocked(UUID tenantId, UUID batchId, UUID itemId, String filename, ScanVerdict verdict,
                         String reason, String rowMessage) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("filename", filename);
        details.put("reason", reason);
        details.put("threatName", verdict.threatName());
        details.put("engine", verdict.engineName());
        details.put("signatureVersion", verdict.signatureVersion());
        try {
            newTransaction.executeWithoutResult(status -> {
                auditService.event(AuditActionCatalog.IMPORT_FILE_BLOCKED)
                        .tenantId(tenantId)
                        .objectType(AuditObjectTypes.IMPORT_BATCH)
                        .objectId(batchId)
                        .resourceName(filename)
                        .outcome(AuditOutcome.DENIED)
                        .newValue(details)
                        .log();
                importItemRepository.findById(itemId).ifPresent(item -> {
                    item.setIntakeBlockedReason(rowMessage.length() > 500 ? rowMessage.substring(0, 500) : rowMessage);
                    importItemRepository.save(item);
                });
            });
        } catch (RuntimeException e) {
            log.error("Could not audit blocked import file '{}' (batch {})", filename, batchId, e);
        }
        log.warn("Blocked bulk import file '{}' (batch {}): {} {}", filename, batchId, reason, verdict.threatName());
    }
}
