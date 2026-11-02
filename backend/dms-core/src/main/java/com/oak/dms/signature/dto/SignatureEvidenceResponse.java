package com.oak.dms.signature.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Everything needed to independently verify a signature offline, e.g. with
 * {@code openssl dgst -sha256 -verify pub.pem -signature sig.der manifest.json}
 * (the manifest text re-encoded to a file, the DER signature base64-decoded):
 * the exact signed manifest text, the DER signature, the public key PEM, and
 * the version's stored file hash the manifest itself names.
 */
public record SignatureEvidenceResponse(
        UUID signatureId,
        UUID documentId,
        UUID versionId,
        String manifestText,
        String signatureDerBase64,
        String publicKeyPem,
        String algorithm,
        String fileSha256,
        String status,
        String lastVerificationResult,
        OffsetDateTime signedAt
) {
}
