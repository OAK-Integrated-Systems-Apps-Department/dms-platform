package com.oak.dms.signature.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/** One signature, as the API returns it (list, verify-all). */
public record DocumentSignatureResponse(
        UUID signatureId,
        UUID documentId,
        UUID versionId,
        int versionNumber,
        /** False when the document has moved on to a newer version since this was signed. */
        boolean currentVersion,
        UUID workflowInstanceId,
        UUID taskId,
        UUID signerUserId,
        String signerName,
        String signerEmail,
        String meaning,
        String comment,
        String status,
        String statusReason,
        OffsetDateTime statusChangedAt,
        String lastVerificationResult,
        OffsetDateTime lastVerifiedAt,
        OffsetDateTime signedAt,
        /** Cosmetic only - see DocumentSignature's own doc comment. Null for a signature made before V109. */
        String visualSignatureKind,
        String visualSignatureText,
        /** Base64 PNG - null when visualSignatureKind isn't "drawn". */
        String visualSignatureImageBase64
) {
}
