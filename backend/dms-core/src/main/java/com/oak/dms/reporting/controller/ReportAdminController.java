package com.oak.dms.reporting.controller;

import com.oak.dms.common.dto.PageResponse;
import com.oak.dms.reporting.admin.ReportAdminService;
import com.oak.dms.reporting.admin.ReportSampleService;
import com.oak.dms.reporting.dto.AdminFieldResponse;
import com.oak.dms.reporting.dto.AdminFieldsUpdate;
import com.oak.dms.reporting.dto.AdminRunResponse;
import com.oak.dms.reporting.dto.AdminScheduleResponse;
import com.oak.dms.reporting.dto.AdminSettingsResponse;
import com.oak.dms.reporting.dto.ReassignRequest;
import com.oak.dms.reporting.dto.ReportSettingsUpdate;
import com.oak.dms.reporting.run.ReportRunStatus;
import com.oak.dms.reporting.schedule.ReportScheduleAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The organisation's report administration: settings, field catalogue, every schedule, and a metadata-only look at every run. None of
 * these answers 403 REPORTS_DISABLED - the administrator has to be able to switch reports back on.
 */
@RestController
@RequestMapping("/api/v1/reports/admin")
@RequiredArgsConstructor
@Tag(name = "Custom reports", description = "Build, preview, run and schedule configurable reports over documents")
public class ReportAdminController {

    private static final String P = "T(com.oak.dms.security.rbac.catalog.PermissionCodes).";
    private static final String CONFIGURE = "@authz.hasPermission(" + P + "CRG_CONFIGURE_REPORTS)";

    private final ReportAdminService admin;
    private final ReportScheduleAdminService scheduleAdmin;
    private final ReportSampleService samples;

    @GetMapping("/settings")
    @PreAuthorize(CONFIGURE)
    @Operation(summary = "The organisation's report settings as they are in force, with the platform ceilings",
            description = "Every value is already capped at its ceiling; a limit the organisation never set shows the platform's.")
    public AdminSettingsResponse settings() {
        return admin.settings();
    }

    @PutMapping("/settings")
    @PreAuthorize(CONFIGURE)
    @Operation(summary = "Replace the organisation's report settings",
            description = "The whole settings object is replaced. 422 REPORT_SETTINGS_INVALID names every field that is outside a "
                    + "platform ceiling or an allowed value. Audited as REPORT_SETTINGS_UPDATED with the old and new values; a save "
                    + "that changes nothing is not audited. Returns what is now in force.")
    public AdminSettingsResponse saveSettings(@NotNull @RequestBody ReportSettingsUpdate update) {
        return admin.saveSettings(update);
    }

    @PostMapping("/settings/sample")
    @PreAuthorize(CONFIGURE)
    @Operation(summary = "A sample file under the settings on screen, before they are saved",
            description = "Five invented rows, no organisation data. The body is the (unsaved) settings; they are validated like a "
                    + "save (422 REPORT_SETTINGS_INVALID) and stored nowhere. format is pdf or csv.")
    public ResponseEntity<byte[]> sample(@RequestParam(defaultValue = "pdf") String format,
                                         @RequestBody(required = false) ReportSettingsUpdate unsaved) {
        ReportSampleService.Sample sample = samples.generate(format, unsaved);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, sample.contentType())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(sample.filename(), StandardCharsets.UTF_8).build().toString())
                .body(sample.bytes());
    }

    @GetMapping("/fields")
    @PreAuthorize(CONFIGURE)
    @Operation(summary = "Every field the report builder can offer, with the administrator's overrides",
            description = "Built-in fields and the organisation's active custom fields, each with what it supports and the override "
                    + "(null when untouched).")
    public List<AdminFieldResponse> fields() {
        return admin.fields();
    }

    @PutMapping("/fields")
    @PreAuthorize(CONFIGURE)
    @Operation(summary = "Replace all field overrides in one transaction",
            description = "A field not listed goes back to the code's defaults. An override can only restrict what a field supports. "
                    + "422 REPORT_FIELD_SETTINGS_INVALID names each problem by field. Audited as REPORT_FIELD_SETTINGS_UPDATED with "
                    + "the changed fields' old and new values.")
    public List<AdminFieldResponse> saveFields(@NotNull @RequestBody AdminFieldsUpdate update) {
        return admin.saveFields(update);
    }

    @GetMapping("/schedules")
    @PreAuthorize(CONFIGURE)
    @Operation(summary = "Every schedule in the organisation, metadata only",
            description = "Owner, template, frequency, next run, status, last failure and how many recipients - not who they are. "
                    + "Filter by status, owner and template.")
    public PageResponse<AdminScheduleResponse> schedules(@RequestParam(required = false) String status,
                                                         @RequestParam(required = false) UUID ownerUserId,
                                                         @RequestParam(required = false) UUID templateId,
                                                         @PageableDefault(size = 20, sort = "name") Pageable pageable) {
        return scheduleAdmin.list(status, ownerUserId, templateId, pageable);
    }

    @PostMapping("/schedules/{scheduleId}/pause")
    @PreAuthorize(CONFIGURE)
    @Operation(summary = "Pause any schedule", description = "Its history is kept. Audited as REPORT_SCHEDULE_PAUSED with reason ADMIN.")
    public ResponseEntity<Void> pause(@PathVariable UUID scheduleId) {
        scheduleAdmin.pause(scheduleId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/schedules/{scheduleId}/resume")
    @PreAuthorize(CONFIGURE)
    @Operation(summary = "Resume a paused schedule",
            description = "409 SCHEDULE_OWNER_INACTIVE while its owner is gone: reassign it first.")
    public ResponseEntity<Void> resume(@PathVariable UUID scheduleId) {
        scheduleAdmin.resume(scheduleId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/schedules/{scheduleId}/cancel")
    @PreAuthorize(CONFIGURE)
    @Operation(summary = "Cancel any schedule", description = "Its history is kept.")
    public ResponseEntity<Void> cancel(@PathVariable UUID scheduleId) {
        scheduleAdmin.cancel(scheduleId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/schedules/{scheduleId}/reassign")
    @PreAuthorize(CONFIGURE)
    @Operation(summary = "Hand a schedule to another user",
            description = "The new owner must be active, may run reports, and can see the template (422 REASSIGN_OWNER_*); a schedule "
                    + "that sends to external addresses also needs an owner who may administer reports. A schedule paused because "
                    + "its owner left starts again.")
    public AdminScheduleResponse reassign(@PathVariable UUID scheduleId, @NotNull @RequestBody ReassignRequest request) {
        return scheduleAdmin.reassign(scheduleId, request.newOwnerUserId());
    }

    @GetMapping("/principals")
    @PreAuthorize(CONFIGURE)
    @Operation(summary = "Search people or roles by name, for the administration pickers",
            description = "type=user|role, q = part of the name (or, for people, the email), limit up to 100. Active ones only. Needs only "
                    + "the report-administration permission, not the user-management ones.")
    public java.util.List<com.oak.dms.reporting.schedule.ReportPeople.Option> principals(
            @RequestParam String type, @RequestParam(required = false) String q, @RequestParam(defaultValue = "20") int limit) {
        return admin.principals(type, q, limit);
    }

    @GetMapping("/runs")
    @PreAuthorize("@authz.hasPermission(" + P + "CRG_VIEW_ALL_RUNS)")
    @Operation(summary = "Every run in the organisation, metadata only",
            description = "That it happened, for whom and how it ended - never its filters, its file or a way to download it: a "
                    + "report's data belongs to the person it was made for.")
    public PageResponse<AdminRunResponse> runs(@RequestParam(required = false) ReportRunStatus status,
                                               @RequestParam(required = false) UUID requestedBy,
                                               @RequestParam(required = false) UUID templateId,
                                               @RequestParam(required = false) UUID scheduleId,
                                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                                               OffsetDateTime from,
                                               @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
                                               Pageable pageable) {
        return admin.allRuns(status, requestedBy, templateId, scheduleId, from, pageable);
    }
}
