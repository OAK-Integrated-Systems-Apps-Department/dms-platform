package com.oak.dms.signature.dto;

import com.oak.dms.signature.enums.SignatureMeaning;
import com.oak.dms.signature.enums.VisualSignatureKind;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * The signing ceremony: explicit {@link #consent} plus step-up
 * re-authentication (password, and {@code totp} when the signer has MFA
 * enabled) alongside the plain approval fields. {@link #toString} is
 * overridden to redact {@link #password}/{@link #totp} - never log or
 * persist this record as-is.
 * <p>
 * {@link #visualSignatureKind}/{@link #visualSignatureText}/
 * {@link #visualSignatureImageBase64} are the cosmetic mark shown alongside
 * the real signature - pre-filled client-side from the signer's saved
 * {@code user_visual_signatures} row (editable here), snapshotted onto this
 * signature's own row, and used to update that saved row for next time. See
 * {@code UserVisualSignatureService}'s own doc comment for why this is never
 * part of what's actually verified.
 */
public record SignAndApproveRequest(
        @Size(max = 2000) String comment,
        @NotNull SignatureMeaning meaning,
        @AssertTrue(message = "Explicit consent to sign is required") boolean consent,
        @NotBlank String password,
        String totp,
        @NotNull VisualSignatureKind visualSignatureKind,
        @Size(max = 150) String visualSignatureText,
        String visualSignatureImageBase64
) {
    @Override
    public String toString() {
        return ("SignAndApproveRequest[comment=%s, meaning=%s, consent=%s, password=REDACTED, totp=REDACTED, "
                + "visualSignatureKind=%s]").formatted(comment, meaning, consent, visualSignatureKind);
    }
}
