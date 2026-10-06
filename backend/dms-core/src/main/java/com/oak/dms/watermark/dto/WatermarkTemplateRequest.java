package com.oak.dms.watermark.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Create or edit one of the tenant's own watermark templates.
 * <p>
 * Every style field is optional, and leaving one out is meaningful: null
 * means "use the system default", so a template that only wants larger text
 * sets {@code fontSize} and nothing else. On update, a field left out is
 * cleared back to the system default rather than kept - this is a PUT, so
 * the body is the whole template.
 * <p>
 * The {@code @Size}/{@code @NotBlank} annotations here only guard the column
 * widths. The real ranges (font size, opacity, rotation, the colour format)
 * are configurable in {@code dms.watermark.style.*} and are checked by
 * {@code WatermarkTemplateService}, which is also what produces the error
 * message naming the allowed range.
 *
 * @param active false retires the template: it keeps naming the jobs already
 *               made from it, but can't be chosen for a new one
 */
public record WatermarkTemplateRequest(
        @NotBlank(message = "Give the template a name.")
        @Size(max = 100, message = "A template name can be at most 100 characters.")
        String label,

        @NotBlank(message = "Say what the watermark should read.")
        @Size(max = 500, message = "Watermark text can be at most 500 characters.")
        String defaultText,

        Integer fontSize,
        Integer opacity,
        Integer rotation,
        String color,
        String placement,
        String pageScope,
        Boolean active
) {
}
