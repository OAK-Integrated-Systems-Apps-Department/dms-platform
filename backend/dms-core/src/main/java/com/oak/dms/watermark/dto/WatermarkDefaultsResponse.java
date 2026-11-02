package com.oak.dms.watermark.dto;

import java.util.UUID;

/**
 * This tenant's watermarking policy, as the API returns it.
 *
 * @param enabled              whether a plain download is watermarked by default
 * @param bulkExportEnabled    whether a bulk/ZIP export's files are watermarked by default
 * @param defaultTemplateId    the template used when {@code enabled} or
 *                             {@code bulkExportEnabled} - null if none is configured
 *                             yet, which the UI should treat the same as "off" until
 *                             one is chosen
 * @param defaultTemplateLabel the template's label, for display without a second
 *                             lookup - null when {@code defaultTemplateId} is null
 */
public record WatermarkDefaultsResponse(
        boolean enabled,
        boolean bulkExportEnabled,
        UUID defaultTemplateId,
        String defaultTemplateLabel
) {

    public static final WatermarkDefaultsResponse OFF = new WatermarkDefaultsResponse(false, false, null, null);
}
