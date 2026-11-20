package com.oak.dms.reporting.controller;

import com.oak.dms.common.dto.PageResponse;
import com.oak.dms.reporting.dto.ScheduleRequest;
import com.oak.dms.reporting.dto.ScheduleResponse;
import com.oak.dms.reporting.dto.ScheduleRunResponse;
import com.oak.dms.reporting.schedule.ReportScheduleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** A person's own report schedules. Someone else's schedule is a 404; the administrator's tenant-wide views are under /admin. */
@RestController
@RequestMapping("/api/v1/reports/schedules")
@RequiredArgsConstructor
@Tag(name = "Custom reports", description = "Build, preview, run and schedule configurable reports over documents")
public class ReportScheduleController {

    private static final String P = "T(com.oak.dms.security.rbac.catalog.PermissionCodes).";
    private static final String CAN_SEE = "@authz.hasAnyPermission(" + P + "CRG_RUN, " + P + "CRG_SCHEDULE_CREATE, "
            + P + "CRG_SCHEDULE_UPDATE_CANCEL)";
    private static final String CAN_CHANGE = "@authz.hasPermission(" + P + "CRG_SCHEDULE_UPDATE_CANCEL)";

    private final ReportScheduleService service;

    /** The runs a run-now started. */
    public record RunNowResponse(List<UUID> runIds) {
    }

    @GetMapping
    @PreAuthorize(CAN_SEE)
    @Operation(summary = "The caller's own schedules (cancelled ones excluded)")
    public List<ScheduleResponse> list() {
        return service.listMine();
    }

    @PostMapping
    @PreAuthorize("@authz.hasPermission(" + P + "CRG_SCHEDULE_CREATE)")
    @Operation(summary = "Schedule a template",
            description = "Daily, weekly (days of the week) or monthly (a day, or the last day), at a time of day in an IANA time zone. "
                    + "Recipients are people, roles or departments - each gets the report under THEIR OWN access - or external "
                    + "addresses, which get it under YOUR access as an attachment and need the report-administration permission. "
                    + "422 SCHEDULE_INVALID lists each problem by field; 409 SCHEDULE_LIMIT_REACHED at the per-person limit.")
    public ResponseEntity<ScheduleResponse> create(@NotNull @RequestBody ScheduleRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request));
    }

    @GetMapping("/{scheduleId}")
    @PreAuthorize(CAN_SEE)
    @Operation(summary = "One schedule", description = "404 SCHEDULE_NOT_FOUND for someone else's schedule.")
    public ScheduleResponse get(@PathVariable UUID scheduleId) {
        return service.get(scheduleId);
    }

    @PutMapping("/{scheduleId}")
    @PreAuthorize(CAN_CHANGE)
    @Operation(summary = "Replace a schedule",
            description = "Everything is checked again as for a new one. Failures so far are forgotten. A schedule paused after repeated "
                    + "failures stays paused until it is resumed.")
    public ScheduleResponse update(@PathVariable UUID scheduleId, @NotNull @RequestBody ScheduleRequest request) {
        return service.update(scheduleId, request);
    }

    @DeleteMapping("/{scheduleId}")
    @PreAuthorize(CAN_CHANGE)
    @Operation(summary = "Cancel a schedule", description = "Its history is kept.")
    public ResponseEntity<Void> cancel(@PathVariable UUID scheduleId) {
        service.cancel(scheduleId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{scheduleId}/pause")
    @PreAuthorize(CAN_CHANGE)
    @Operation(summary = "Pause a schedule", description = "No runs until it is resumed. Nothing is deleted.")
    public ScheduleResponse pause(@PathVariable UUID scheduleId) {
        return service.pause(scheduleId);
    }

    @PostMapping("/{scheduleId}/resume")
    @PreAuthorize(CAN_CHANGE)
    @Operation(summary = "Resume a paused schedule",
            description = "The next run is worked out from now; missed slots are not made up.")
    public ScheduleResponse resume(@PathVariable UUID scheduleId) {
        return service.resume(scheduleId);
    }

    @PostMapping("/{scheduleId}/run-now")
    @PreAuthorize("@authz.hasPermission(" + P + "CRG_RUN)")
    @Operation(summary = "Run a schedule once, now",
            description = "Delivers exactly as a scheduled run would, to the same recipients, and does not move the next run. "
                    + "422 SCHEDULE_CANNOT_RUN says why when it cannot (e.g. the template is gone).")
    public ResponseEntity<RunNowResponse> runNow(@PathVariable UUID scheduleId) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new RunNowResponse(service.runNow(scheduleId)));
    }

    @GetMapping("/{scheduleId}/runs")
    @PreAuthorize(CAN_SEE)
    @Operation(summary = "A schedule's run history, newest first",
            description = "Each run with what happened to its deliveries - counts of sent, failed, pending and skipped, never addresses.")
    public PageResponse<ScheduleRunResponse> history(@PathVariable UUID scheduleId,
                                                     @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
                                                     Pageable pageable) {
        return service.history(scheduleId, pageable);
    }
}
