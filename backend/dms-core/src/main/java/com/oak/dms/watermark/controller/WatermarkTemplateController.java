package com.oak.dms.watermark.controller;

import com.oak.dms.watermark.dto.WatermarkTemplateRequest;
import com.oak.dms.watermark.dto.WatermarkTemplateResponse;
import com.oak.dms.watermark.service.WatermarkTemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Watermark templates (module DW) - see {@link WatermarkTemplateService}.
 * Anyone who can open a document can list them, because choosing one is part
 * of asking for a watermarked copy; creating and editing need the matching
 * {@code dw.configure-watermark-policy.*} code. Deleting reuses the update
 * code - V10's catalogue has no delete-template permission, and the service
 * comment explains why adding one would cost more than it is worth.
 * <p>
 * The whole controller is conditional on {@code dms.watermark.enabled}.
 * While the feature is off these paths are not mapped at all, so they answer
 * a genuine 404 through the existing {@code NoResourceFoundException}
 * handler rather than existing and refusing - and they stay out of the
 * OpenAPI document, which is why that document has to be generated with the
 * flag on.
 */
@RestController
@RequestMapping("/api/v1/watermark-templates")
@ConditionalOnProperty(prefix = "dms.watermark", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
@Tag(name = "Watermark templates", description = "Named watermark text and style, per tenant")
public class WatermarkTemplateController {

    private static final String P = "T(com.oak.dms.security.rbac.catalog.PermissionCodes).";
    private static final String CAN_CREATE = "@authz.hasPermission(" + P + "DW_WATERMARK_TEMPLATE_CREATE)";
    private static final String CAN_UPDATE = "@authz.hasPermission(" + P + "DW_WATERMARK_TEMPLATE_UPDATE)";
    /** Picking a template is part of watermarking a document, so viewing documents is enough to list them. */
    private static final String CAN_USE = "@authz.hasPermission(" + P + "DOCM_DOCUMENTS_VIEW) or "
            + CAN_CREATE + " or " + CAN_UPDATE;

    private final WatermarkTemplateService watermarkTemplateService;

    @GetMapping
    @PreAuthorize(CAN_USE)
    @Operation(summary = "The watermark templates this tenant can use",
            description = "The tenant's own templates and the built-in ones, built-in first then by name. "
                    + "includeInactive=true also returns retired templates - for the admin screen; the "
                    + "default of false is what a user picking a watermark should see.")
    public List<WatermarkTemplateResponse> list(
            @RequestParam(name = "includeInactive", defaultValue = "false") boolean includeInactive) {
        return watermarkTemplateService.list(includeInactive);
    }

    @GetMapping("/{templateId}")
    @PreAuthorize(CAN_USE)
    @Operation(summary = "One watermark template")
    public WatermarkTemplateResponse get(@PathVariable UUID templateId) {
        return watermarkTemplateService.get(templateId);
    }

    @PostMapping
    @PreAuthorize(CAN_CREATE)
    @Operation(summary = "Create a watermark template for this tenant",
            description = "Style fields are optional - leaving one out means the system default, so a "
                    + "template can set only what it cares about.")
    public ResponseEntity<WatermarkTemplateResponse> create(@Valid @RequestBody WatermarkTemplateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(watermarkTemplateService.create(request));
    }

    @PutMapping("/{templateId}")
    @PreAuthorize(CAN_UPDATE)
    @Operation(summary = "Edit a watermark template, or retire it with active=false",
            description = "The body is the whole template: a style field left out is cleared back to the "
                    + "system default. Built-in templates can't be edited (403).")
    public WatermarkTemplateResponse update(@PathVariable UUID templateId,
                                            @Valid @RequestBody WatermarkTemplateRequest request) {
        return watermarkTemplateService.update(templateId, request);
    }

    @DeleteMapping("/{templateId}")
    @PreAuthorize(CAN_UPDATE)
    @Operation(summary = "Delete a watermark template",
            description = "A soft delete. Copies already produced from it keep their own record of what "
                    + "they say, so removing a template never changes or breaks one. Built-in templates "
                    + "can't be deleted (403).")
    public ResponseEntity<Void> delete(@PathVariable UUID templateId) {
        watermarkTemplateService.delete(templateId);
        return ResponseEntity.noContent().build();
    }
}
