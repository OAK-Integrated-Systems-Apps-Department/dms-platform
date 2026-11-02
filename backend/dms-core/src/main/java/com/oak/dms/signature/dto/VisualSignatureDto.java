package com.oak.dms.signature.dto;

import java.time.OffsetDateTime;

/** The caller's own saved visual signature (GET /api/v1/users/me/visual-signature), or a 404 if they've never set one. */
public record VisualSignatureDto(
        String kind,
        String typedText,
        /** Base64 PNG - null when {@code kind} is "typed". */
        String imageBase64,
        OffsetDateTime updatedAt
) {
}
