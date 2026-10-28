package com.oak.dms.relationship.listener;

import com.oak.dms.relationship.event.DuplicateKeptEvent;
import com.oak.dms.relationship.service.DuplicateOfLinker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Links a kept copy to its original once the new document is committed (DRM-FR-005). Whatever goes
 * wrong here is logged and dropped: the document is already there and the upload has succeeded.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DuplicateOfLinkListener {

    private final DuplicateOfLinker linker;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDuplicateKept(DuplicateKeptEvent event) {
        try {
            linker.link(event);
        } catch (RuntimeException e) {
            log.warn("Could not link new document {} to {} as a duplicate: {}", event.newDocumentId(),
                    event.originalDocumentId(), e.toString());
        }
    }
}
