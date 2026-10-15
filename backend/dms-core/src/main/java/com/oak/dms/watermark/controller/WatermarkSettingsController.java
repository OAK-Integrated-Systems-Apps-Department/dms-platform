package com.oak.dms.watermark.controller;

import com.oak.dms.watermark.dto.WatermarkDefaultsRequest;
import com.oak.dms.watermark.dto.WatermarkDefaultsResponse;
import com.oak.dms.watermark.service.WatermarkDefaultsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * This tenant's policy for the plain "Download" action (module DW, V103):
 * whether it watermarks the file inline, synchronously, by default, and with
 * which template.
 * <p>
 * A separate controller from {@link WatermarkTemplateController} rather than
 * more endpoints bolted onto it, because its URL ({@code /watermark-settings})
 * and its resource (one row per tenant, not a collection) are both different
 * - but it is gated by the exact same {@code dw.configure-watermark-policy.*}
 * codes, since "who may decide the download default" is the same population
 * as "who may manage templates", and reuses {@link WatermarkDefaultsService}
 * rather than a new permission.
 * <p>
 * Conditional on {@code dms.watermark.enabled} like every other watermark
 * controller, for the same reason: while the feature is off these paths
 * should 404 as if they don't exist, not exist and refuse.
 */
@RestController
@RequestMapping("/api/v1/watermark-settings")
@ConditionalOnProperty(prefix = "dms.watermark", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
@Tag(name = "Watermark settings", description = "Default watermarking policy for plain downloads, per tenant")
public class WatermarkSettingsController {

    private static final String P = "T(com.oak.dms.security.rbac.catalog.PermissionCodes).";
    private static final String CAN_CONFIGURE = "@authz.hasPermission(" + P + "DW_WATERMARK_TEMPLATE_CREATE) or "
            + "@authz.hasPermission(" + P + "DW_WATERMARK_TEMPLATE_UPDATE)";

    private final WatermarkDefaultsService watermarkDefaultsService;

    @GetMapping
    @PreAuthorize(CAN_CONFIGURE)
    @Operation(summary = "This tenant's download-watermarking policy")
    public WatermarkDefaultsResponse get() {
        return watermarkDefaultsService.get();
    }

    @PutMapping
    @PreAuthorize(CAN_CONFIGURE)
    @Operation(summary = "Turn default download watermarking on or off, and choose its template",
            description = "enabled=true requires templateId to name a template this tenant may use (its own, "
                    + "or a built-in one) that is still active.")
    public WatermarkDefaultsResponse update(@Valid @RequestBody WatermarkDefaultsRequest request) {
        return watermarkDefaultsService.update(request);
    }
}
