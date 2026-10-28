package com.oak.dms.watermark.dto;

import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Ask for a watermarked copy of one document.
 * <p>
 * Exactly one of {@code templateId} or {@code customText} - neither is a
 * request with nothing to say, and both is a request whose intent is
 * ambiguous. Picking one silently would mean a user who edited the text
 * while a template was still selected gets the template's wording on their
 * document and no indication why.
 * <p>
 * Every style field is optional and overrides the template, which in turn
 * overrides the system default ({@code dms.watermark.style.*}).
 *
 * @param versionId a specific version, or null for the document's current
 *                  one. Naming one additionally requires
 *                  {@code dvc.view-version-history.view}, the same
 *                  permission the version download endpoints use.
 */
public record WatermarkRequest(
        UUID templateId,

        @Size(max = 500, message = "Watermark text can be at most 500 characters.")
        String customText,

        UUID versionId,
        Integer fontSize,
        Integer opacity,
        Integer rotation,
        String color,
        String placement,
        String pageScope
) {

    public boolean hasTemplate() {
        return templateId != null;
    }

    public boolean hasCustomText() {
        return customText != null && !customText.isBlank();
    }
}
