package com.oak.dms.reporting.controller;

import com.oak.dms.reporting.dto.TemplatePublishRequest;
import com.oak.dms.reporting.dto.TemplateRequest;
import com.oak.dms.reporting.dto.TemplateResponse;
import com.oak.dms.reporting.dto.TemplateShareRequest;
import com.oak.dms.reporting.template.ReportTemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
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

/** Saved report definitions: the caller's own, those shared with them, and the organisation's official ones. */
@RestController
@RequestMapping("/api/v1/reports/templates")
@RequiredArgsConstructor
@Tag(name = "Custom reports", description = "Build, preview, run and schedule configurable reports over documents")
public class ReportTemplateController {

    private static final String P = "T(com.oak.dms.security.rbac.catalog.PermissionCodes).";
    private static final String CAN_SEE = "@authz.hasAnyPermission(" + P + "CRG_RUN, " + P + "CRG_BUILD)";

    private final ReportTemplateService service;

    @GetMapping
    @PreAuthorize(CAN_SEE)
    @Operation(summary = "The templates the caller may see or run",
            description = "Their own, those shared with them (through them, one of their roles or their department) and the "
                    + "organisation's official ones. `access` says what they may do: owner, edit or run. `warnings` lists fields an "
                    + "administrator has since switched off - the template still runs without them.")
    public List<TemplateResponse> list() {
        return service.list();
    }

    @PostMapping
    @PreAuthorize("@authz.hasPermission(" + P + "CRG_TEMPLATE_CREATE)")
    @Operation(summary = "Save a definition as a private template",
            description = "The definition is checked against the organisation's field catalogue. 409 TEMPLATE_NAME_TAKEN for a name "
                    + "you already use (case-insensitive); 409 TEMPLATE_LIMIT_REACHED at the organisation's per-person limit.")
    public ResponseEntity<TemplateResponse> create(@NotNull @RequestBody TemplateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request));
    }

    @GetMapping("/{templateId}")
    @PreAuthorize(CAN_SEE)
    @Operation(summary = "One template",
            description = "404 TEMPLATE_NOT_FOUND for a template the caller may not see, exactly as for one that does not exist.")
    public TemplateResponse get(@PathVariable UUID templateId) {
        return service.get(templateId);
    }

    @PutMapping("/{templateId}")
    @PreAuthorize("@authz.hasPermission(" + P + "CRG_TEMPLATE_UPDATE)")
    @Operation(summary = "Replace a template's name, description and definition",
            description = "For the owner and anyone it was shared with for editing; an official template needs the publish "
                    + "permission. 403 TEMPLATE_READ_ONLY when it was shared to run only.")
    public TemplateResponse update(@PathVariable UUID templateId, @NotNull @RequestBody TemplateRequest request) {
        return service.update(templateId, request);
    }

    @DeleteMapping("/{templateId}")
    @PreAuthorize("@authz.hasPermission(" + P + "CRG_TEMPLATE_DELETE)")
    @Operation(summary = "Delete a template (soft delete)",
            description = "409 TEMPLATE_HAS_SCHEDULES with the number of schedules while any are active or paused. "
                    + "`cancelSchedules=true` cancels them in the same transaction.")
    public ResponseEntity<Void> delete(@PathVariable UUID templateId, @RequestParam(defaultValue = "false") boolean cancelSchedules) {
        service.delete(templateId, cancelSchedules);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{templateId}/duplicate")
    @PreAuthorize("@authz.hasPermission(" + P + "CRG_TEMPLATE_CREATE)")
    @Operation(summary = "Save a copy as the caller's own private template",
            description = "Works on anything the caller may see, except a locked official template (403 TEMPLATE_LOCKED) unless "
                    + "they can publish. The optional name defaults to \"<name> (copy)\".")
    public ResponseEntity<TemplateResponse> duplicate(@PathVariable UUID templateId, @RequestParam(required = false) String name) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.duplicate(templateId, name));
    }

    @PostMapping("/{templateId}/share")
    @PreAuthorize("@authz.hasPermission(" + P + "CRG_TEMPLATE_UPDATE)")
    @Operation(summary = "Replace who a template is shared with",
            description = "Owner only. Each grant is a user, role or department with access `run` or `edit`. An empty list makes the "
                    + "template private again. Audited as REPORT_TEMPLATE_SHARED with a count, not the names.")
    public TemplateResponse share(@PathVariable UUID templateId, @NotNull @RequestBody TemplateShareRequest request) {
        return service.share(templateId, request);
    }

    @PostMapping("/{templateId}/publish")
    @PreAuthorize("@authz.hasPermission(" + P + "CRG_TEMPLATE_PUBLISH)")
    @Operation(summary = "Publish the caller's template as the organisation's own",
            description = "The template then has no owner, so it outlives its author. Optionally locked (people can run it and change "
                    + "only the filters you list) and limited to an audience; with no audience it is for everyone who may run reports.")
    public TemplateResponse publish(@PathVariable UUID templateId, @NotNull @RequestBody TemplatePublishRequest request) {
        return service.publish(templateId, request);
    }

    @PostMapping("/{templateId}/unpublish")
    @PreAuthorize("@authz.hasPermission(" + P + "CRG_TEMPLATE_PUBLISH)")
    @Operation(summary = "Take an official template back as the caller's private one",
            description = "409 TEMPLATE_NAME_TAKEN if the caller already has a template with that name.")
    public TemplateResponse unpublish(@PathVariable UUID templateId) {
        return service.unpublish(templateId);
    }
}
