package com.oak.dms.signature.dto;

import com.oak.dms.signature.enums.VisualSignatureKind;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Setting/replacing the caller's own saved visual signature. Exactly one of
 * {@code typedText}/{@code imageBase64} is required, matching {@code kind} -
 * checked in {@code UserVisualSignatureService}, not here, since it's a
 * cross-field rule {@code jakarta.validation} can't express as cleanly as a
 * plain if/else.
 * <p>
 * {@link #password} is step-up re-verification (no TOTP - MFA isn't in use
 * yet), required here precisely because this standalone endpoint has
 * nothing else proving it's really this person, unlike editing a signature
 * as part of an actual sign-and-approve. Never logged or persisted - see
 * {@link #toString}.
 */
public record UpsertVisualSignatureRequest(
        @NotNull VisualSignatureKind kind,
        @Size(max = 150) String typedText,
        /** Base64-encoded PNG from the signing canvas - size-capped in the service, not here (base64 inflates it ~33%). */
        String imageBase64,
        @NotBlank String password
) {
    @Override
    public String toString() {
        return "UpsertVisualSignatureRequest[kind=%s, typedText=%s, imageBase64=%s, password=REDACTED]"
                .formatted(kind, typedText, imageBase64 == null ? "null" : "<" + imageBase64.length() + " chars>");
    }
}
