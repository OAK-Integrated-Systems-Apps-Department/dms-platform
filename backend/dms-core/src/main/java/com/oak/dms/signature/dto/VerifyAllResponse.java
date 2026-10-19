package com.oak.dms.signature.dto;

import java.util.List;

/** The result of re-verifying every one of a document's signatures on demand (DSI-FR-003). */
public record VerifyAllResponse(
        int total,
        int valid,
        int invalidated,
        int unreadable,
        List<DocumentSignatureResponse> signatures
) {
}
