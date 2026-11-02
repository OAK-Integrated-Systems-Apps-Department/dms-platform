package com.oak.dms.signature.listener;

import com.oak.dms.signature.repository.DocumentSignatureRepository;
import com.oak.dms.signature.service.DocumentSignatureLockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Safety net for {@code WorkflowSignatureListener#onApproved}: that listener
 * locks a document right after its workflow's approval commits, but an
 * AFTER_COMMIT listener can fail to run at all (the JVM crashes between
 * commit and the listener firing, for one) - this periodically finds any
 * approved-and-validly-signed document that is still unlocked and locks it.
 * {@code DocumentSignatureLockService#lock} is idempotent, so running this on
 * every application instance concurrently is harmless - at most a few
 * instances race to lock the same document and all but one find it already
 * locked.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SignatureLockReconcileJob {

    private static final int BATCH = 200;

    private final DocumentSignatureRepository signatureRepository;
    private final DocumentSignatureLockService lockService;

    @Scheduled(fixedDelayString = "${dms.signature.reconcile-interval:PT15M}", initialDelayString = "PT1M")
    public void reconcile() {
        List<DocumentSignatureRepository.PendingLock> pending = signatureRepository.findPendingLocks(BATCH);
        if (pending.isEmpty()) {
            return;
        }
        log.info("Signature lock reconcile: {} document(s) approved-and-signed but not yet locked", pending.size());
        for (DocumentSignatureRepository.PendingLock row : pending) {
            lockService.lock(row.getTenantId(), row.getDocumentId(), row.getVersionId());
        }
    }
}
