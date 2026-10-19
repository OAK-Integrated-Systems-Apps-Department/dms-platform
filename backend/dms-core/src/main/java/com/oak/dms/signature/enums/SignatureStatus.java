package com.oak.dms.signature.enums;

/**
 * A signature row's lifecycle status (V108). {@code valid} is a signature
 * that still stands behind the version it signed; {@code invalidated}
 * covers both reasons a signature stops standing behind it - its workflow
 * ended cancelled/rejected (see {@code WorkflowSignatureListener}), or a
 * later verification found the document altered or the signature wrong
 * (see {@code DocumentSignatureService#verify}). Either way the row itself
 * is never deleted or rewritten - only this status (plus
 * status_changed_at/status_reason) changes.
 */
public enum SignatureStatus {
    valid, invalidated
}
