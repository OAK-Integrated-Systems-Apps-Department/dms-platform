package com.oak.dms.signature.event;

import java.util.List;
import java.util.UUID;

/**
 * A workflow instance ended cancelled or rejected with at least one
 * previously-valid signature, which {@code WorkflowSignatureListener} just
 * invalidated - published after commit so a mail listener can tell the
 * initiator (and whoever would sign again) that their prior signature no
 * longer stands. Never published for a signature invalidated by a failed
 * verification instead - see {@code DOCUMENT_SIGNATURE_VERIFICATION_FAILED}
 * for that path's own admin-alerting event.
 */
public record SignatureInvalidatedEvent(
        UUID tenantId,
        UUID instanceId,
        UUID documentId,
        List<UUID> signatureIds,
        String reason
) {
}
