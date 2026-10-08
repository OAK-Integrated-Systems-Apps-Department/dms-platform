package com.oak.dms.watermark.dto;

import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Render a sample of what a watermark would look like, without touching a
 * document.
 * <p>
 * The style fields mean exactly what they mean on a real request, so an
 * admin tuning a template sees what a user will get. The placeholders are
 * filled with obvious sample values rather than the caller's own details -
 * see {@code WatermarkPreviewService} - because the point is to show the
 * SHAPE of the mark, and a preview that quietly contained somebody's real
 * email address would be an odd thing to hand around a configuration screen.
 */
public record WatermarkPreviewRequest(
        UUID templateId,

        @Size(max = 500, message = "Watermark text can be at most 500 characters.")
        String customText,

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
