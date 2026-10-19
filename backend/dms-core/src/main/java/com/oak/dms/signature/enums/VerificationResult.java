package com.oak.dms.signature.enums;

/**
 * The outcome of replaying a signature's stored manifest and bytes against
 * its stored public key (DocumentSignatureService#verify). {@code unreadable}
 * (the version's bytes couldn't be read right now - storage down, etc.)
 * changes nothing on the row: it is inconclusive, not evidence of tampering,
 * so neither the signature's status nor last_verification_result is written
 * for it.
 */
public enum VerificationResult {
    valid, signature_mismatch, document_altered, unreadable
}
