package com.oak.dms.reporting.controller;

import com.oak.dms.common.dto.PageResponse;
import com.oak.dms.reporting.dto.RunRequest;
import com.oak.dms.reporting.dto.RunResponse;
import com.oak.dms.reporting.run.ReportRunService;
import com.oak.dms.reporting.run.ReportRunStatus;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Report runs: ask for a file, watch it, cancel, retry, download. Every run is the caller's own; anyone else's is a 404. */
@RestController
@RequestMapping("/api/v1/reports/runs")
@RequiredArgsConstructor
@Tag(name = "Custom reports", description = "Build, preview, run and schedule configurable reports over documents")
public class ReportRunController {

    private static final String P = "T(com.oak.dms.security.rbac.catalog.PermissionCodes).";
    private static final String CAN_RUN = "@authz.hasPermission(" + P + "CRG_RUN) and @authz.hasPermission(" + P + "DOCM_DOCUMENTS_VIEW)";
    private static final String CAN_SEE_RUNS = "@authz.hasAnyPermission(" + P + "CRG_RUN, " + P + "CRG_BUILD)";

    private final ReportRunService service;

    @PostMapping
    @PreAuthorize("@authz.hasPermission(" + P + "CRG_RUN)")
    @Operation(summary = "Ask for a report file to be generated",
            description = "Always asynchronous: answers 202 with the run, and a worker produces the file. Poll GET /runs/{id}, or "
                    + "wait for the notification. Needs the run permission, and the build permission when the definition is the "
                    + "caller's own. 422 with a stable code for an invalid definition or a format the organisation has not "
                    + "enabled; 429 REPORT_RATE_LIMITED when the caller or their organisation already has too many running.")
    public ResponseEntity<RunResponse> create(@NotNull @RequestBody RunRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.request(request));
    }

    @GetMapping
    @PreAuthorize(CAN_SEE_RUNS)
    @Operation(summary = "The caller's own runs, newest first",
            description = "Defaults to the last 30 days. Filter by status (queued, running, completed, failed, cancelled, expired, "
                    + "skipped), template and the earliest creation time.")
    public PageResponse<RunResponse> list(
            @RequestParam(required = false) ReportRunStatus status,
            @RequestParam(required = false) UUID templateId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return PageResponse.from(service.listMine(status, templateId, from, pageable));
    }

    @GetMapping("/{runId}")
    @PreAuthorize(CAN_SEE_RUNS)
    @Operation(summary = "Status, progress, row count and error of one of the caller's runs",
            description = "404 REPORT_RUN_NOT_FOUND for a run that is someone else's - exactly as for one that does not exist.")
    public RunResponse get(@PathVariable UUID runId) {
        return service.get(runId);
    }

    @GetMapping("/{runId}/download")
    @PreAuthorize(CAN_RUN)
    @Operation(summary = "Download a finished report file",
            description = "Streams the file after checking again that it is the caller's, that they still hold the run permission "
                    + "and access to documents, and that reports are still on for their organisation. 410 REPORT_EXPIRED once the "
                    + "file has expired; 409 REPORT_NOT_READY while it is being made; 404 REPORT_NO_FILE for a report that found "
                    + "no data. Audited as REPORT_DOWNLOADED.")
    public ResponseEntity<StreamingResponseBody> download(@PathVariable UUID runId) {
        ReportRunService.Download file = service.download(runId);
        StreamingResponseBody body = out -> {
            try (InputStream content = file.content()) {
                content.transferTo(out);
            }
        };
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(file.sizeBytes()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.filename(), StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(body);
    }

    @PostMapping("/{runId}/cancel")
    @PreAuthorize(CAN_SEE_RUNS)
    @Operation(summary = "Cancel one of the caller's runs while it is queued or running",
            description = "409 REPORT_RUN_STATE_INVALID once it has finished.")
    public RunResponse cancel(@PathVariable UUID runId) {
        return service.cancel(runId);
    }

    @PostMapping("/{runId}/retry")
    @PreAuthorize("@authz.hasPermission(" + P + "CRG_RUN)")
    @Operation(summary = "Run the same report again after a failure",
            description = "Creates a new run from the failed run's snapshot, on current data and with the caller's current access. "
                    + "409 REPORT_RUN_NOT_RETRYABLE unless it is the caller's own failed run.")
    public ResponseEntity<RunResponse> retry(@PathVariable UUID runId) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.retry(runId));
    }
}
