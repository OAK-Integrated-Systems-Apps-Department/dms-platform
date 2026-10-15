package com.oak.dms.watermark.dto;

import java.util.UUID;

/**
 * Sets this tenant's download-watermarking policy.
 * <p>
 * {@code templateId} is required when {@code enabled} is true - checked by
 * {@code WatermarkDefaultsService}, which also enforces that it names a
 * template this tenant may actually use (its own or a system one) and that
 * is still active, by calling into {@code WatermarkTemplateService} rather
 * than re-checking tenancy and status here.
 */
public record WatermarkDefaultsRequest(
        boolean enabled,
        UUID templateId
) {
}
