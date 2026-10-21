package com.oak.dms.signature.dto;

import com.oak.dms.signature.enums.VisualSignatureKind;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Where to stamp the signer's visual signature on the document - a separate,
 * non-transactional step BEFORE sign-and-approve (see
 * {@code DocumentSignatureService}'s own doc comment for why). {@code page}
 * is 0-based; the four ratios are each 0..1 of that page/image's own
 * width/height, resolution-independent of whatever size the placement UI
 * happened to render the preview at.
 */
public record PlaceSignatureRequest(
        @Min(0) int page,
        @NotNull @DecimalMin("0.0") @DecimalMax("1.0") Double xRatio,
        @NotNull @DecimalMin("0.0") @DecimalMax("1.0") Double yRatio,
        @NotNull @DecimalMin("0.01") @DecimalMax("1.0") Double widthRatio,
        @NotNull @DecimalMin("0.01") @DecimalMax("1.0") Double heightRatio,
        @NotNull VisualSignatureKind visualSignatureKind,
        @Size(max = 150) String visualSignatureText,
        String visualSignatureImageBase64
) {
}
