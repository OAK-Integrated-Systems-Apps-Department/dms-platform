package com.oak.dms.signature.event;

import java.util.UUID;

/**
 * An on-demand verification (verify-all, or the evidence endpoint) found a
 * signature that no longer checks out - the document was altered after
 * signing, or the signature itself doesn't match. Published after the
 * signature row is marked invalidated, for a mail listener to alert tenant
 * admins (DOCUMENT_SIGNATURE_VERIFICATION_FAILED, HIGH).
 */
public record SignatureVerificationFailedEvent(
        UUID tenantId,
        UUID signatureId,
        UUID documentId,
        String verificationResult
) {
}
