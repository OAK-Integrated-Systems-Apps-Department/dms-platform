package com.oak.dms.watermark.dto;

import com.oak.dms.watermark.entity.WatermarkTemplate;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One watermark template as the API returns it.
 * <p>
 * A style field is null when the template doesn't set it, which the client
 * should show as the system default rather than as empty - the same null the
 * request accepts, so a round trip through an edit form doesn't invent
 * values the template never had.
 *
 * @param systemTemplate true for the four read-only templates every tenant
 *                       sees; the client uses it to hide Edit and Delete
 * @param editable       whether THIS caller's tenant may change it - false
 *                       for a system template, so the client doesn't have to
 *                       work the rule out for itself
 * @param version        bumped on every edit, and recorded on each job made
 *                       from this template
 */
public record WatermarkTemplateResponse(
        UUID templateId,
        String label,
        String defaultText,
        Integer fontSize,
        Integer opacity,
        Integer rotation,
        String color,
        String placement,
        String pageScope,
        boolean systemTemplate,
        boolean editable,
        boolean active,
        int version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {

    public static WatermarkTemplateResponse from(WatermarkTemplate template) {
        return new WatermarkTemplateResponse(
                template.getTemplateId(),
                template.getLabel(),
                template.getDefaultText(),
                template.getFontSize(),
                template.getOpacity(),
                template.getRotation(),
                template.getColor(),
                template.getPlacement() == null ? null : template.getPlacement().getValue(),
                template.getPageScope() == null ? null : template.getPageScope().getValue(),
                template.isSystemDefault(),
                !template.isSystemDefault(),
                template.isActive(),
                template.getVersion(),
                template.getCreatedAt(),
                template.getUpdatedAt());
    }
}
