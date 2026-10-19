package com.oak.dms.watermark.dto;

import java.util.UUID;

/**
 * Sets this tenant's watermarking policy for the plain "Download" action
 * ({@code enabled}) and for a bulk/ZIP export's files ({@code bulkExportEnabled}).
 * <p>
 * {@code templateId} is required when either flag is true - checked by
 * {@code WatermarkDefaultsService}, which also enforces that it names a
 * template this tenant may actually use (its own or a system one) and that
 * is still active, by calling into {@code WatermarkTemplateService} rather
 * than re-checking tenancy and status here.
 */
public record WatermarkDefaultsRequest(
        boolean enabled,
        boolean bulkExportEnabled,
        UUID templateId
) {
}
