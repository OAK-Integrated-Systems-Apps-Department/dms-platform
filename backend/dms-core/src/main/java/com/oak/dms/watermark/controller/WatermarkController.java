package com.oak.dms.watermark.controller;

import com.oak.dms.common.dto.ErrorResponse;
import com.oak.dms.common.dto.PageResponse;
import com.oak.dms.watermark.dto.WatermarkJobResponse;
import com.oak.dms.watermark.dto.WatermarkPreviewRequest;
import com.oak.dms.watermark.dto.WatermarkRequest;
import com.oak.dms.watermark.exception.WatermarkQueueFullException;
import com.oak.dms.watermark.service.WatermarkJobService;
import com.oak.dms.watermark.service.WatermarkPreviewService;
import com.oak.dms.watermark.service.WatermarkRateLimiter;
import com.oak.dms.watermark.service.WatermarkService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.InputStream;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Asking for a watermarked copy, following it, and collecting it.
 *
 * <h2>Paths</h2>
 * This controller owns {@code /api/v1/documents/{id}/watermark} as well as
 * {@code /api/v1/watermark-jobs/**}, so {@code DocumentController} is not
 * touched by this feature at all. The document path is the natural place for
 * "do something to this document", and keeping it here means the whole
 * feature can be switched off with one flag.
 *
 * <h2>Permissions</h2>
 * A watermarked copy shows the person exactly what an ordinary download
 * already would, so asking for one needs {@code docm.documents.view} plus
 * the folder's own view level - no more. Naming a specific
 * {@code versionId} additionally needs
 * {@code dvc.view-version-history.view}, matching the version download
 * endpoints on {@code DocumentController}; without that, a caller who may
 * only see the current version could use this to read an older one.
 */
@RestController
@ConditionalOnProperty(prefix = "dms.watermark", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
@Tag(name = "Watermarking", description = "Watermarked copies of documents, in their own format")
public class WatermarkController {

    private static final String P = "T(com.oak.dms.security.rbac.catalog.PermissionCodes).";
    private static final String CAN_VIEW_DOCUMENTS = "@authz.hasPermission(" + P + "DOCM_DOCUMENTS_VIEW)";
    private static final String CAN_VIEW_VERSIONS = "@authz.hasPermission(" + P + "DVC_VERSIONS_VIEW)";
    private static final String CAN_CONFIGURE =
            "@authz.hasPermission(" + P + "DW_WATERMARK_TEMPLATE_CREATE) or "
                    + "@authz.hasPermission(" + P + "DW_WATERMARK_TEMPLATE_UPDATE)";

    private final WatermarkService watermarkService;
    private final WatermarkJobService watermarkJobService;
    private final WatermarkPreviewService previewService;
    private final WatermarkRateLimiter rateLimiter;

    /**
     * {@code versionId} is checked inside the expression rather than in the
     * method body so the refusal is a clean 403 from the security layer,
     * and so the rule is visible next to the endpoint rather than buried.
     */
    @PostMapping("/api/v1/documents/{documentId}/watermark")
    @PreAuthorize(CAN_VIEW_DOCUMENTS + " and (#request.versionId() == null or " + CAN_VIEW_VERSIONS + ")")
    @Operation(summary = "Ask for a watermarked copy of a document",
            description = """
                    Supply exactly one of templateId or customText. Style fields override the template, \
                    which overrides the system defaults. Produced in the background: poll the job, then \
                    fetch its result. Send an Idempotency-Key header so a repeated click returns the same \
                    job instead of producing a second copy. Naming a versionId needs \
                    dvc.view-version-history.view as well.

                    Placeholders, resolved once at request time and frozen onto the job: {username} \
                    (the requester's DISPLAY NAME, falling back to their login username only when that \
                    is blank), {userEmail}, {date} (UTC, labelled), {documentId}, {documentVersion}, \
                    {watermarkId} (the first 8 characters of the job id, so a leaked copy traces back \
                    to its audit entry). An unknown token is a 400, never printed literally.

                    The copy is always in the ORIGINAL's format - nothing is converted, and the original \
                    is never modified. Supported: PDF, Word (.docx), Excel (.xlsx), PowerPoint (.pptx), \
                    JPEG and PNG. Anything else, including legacy .doc/.xls/.ppt, is a 415.

                    pageScope=first-page-only means something different in each format, because "page" \
                    does: PDF - page 1; Word - page 1, which is done by turning on the section's title \
                    page (its existing first-page header content is preserved); Excel - the first VISIBLE \
                    worksheet, since a spreadsheet has no pages until it is printed; PowerPoint - slide 1; \
                    images - the whole image, which is one page by definition.

                    Known limitations. (1) In Word, Excel and PowerPoint the watermark is an ordinary \
                    picture, so a recipient who opens the file can delete it; Phase 1 makes provenance \
                    visible, it does not make it tamper-proof. (2) The in-app preview may not show the \
                    mark - check the downloaded file in desktop Office or LibreOffice. (3) In Excel the \
                    mark floats ABOVE the cells (Excel offers nothing behind them), which is why opacity \
                    has a floor; a floating picture in desktop Excel can also take the click when \
                    somebody selects a cell underneath it, so a recipient may need to click past it or \
                    drag it aside to edit those cells. (4) A JPEG copy is re-encoded and loses EXIF \
                    metadata, including GPS. \
                    (5) A digitally signed document is refused with 422 - any change would break its \
                    signature. (6) Password-protected files are refused with 422.""")
    public ResponseEntity<WatermarkJobResponse> request(
            @PathVariable UUID documentId,
            @Valid @RequestBody WatermarkRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest httpRequest) {

        rateLimiter.checkWatermarkRequest(httpRequest);
        WatermarkJobResponse job = watermarkService.apply(documentId, request, idempotencyKey);
        return ResponseEntity.accepted()
                .location(URI.create("/api/v1/watermark-jobs/" + job.jobId()))
                .body(job);
    }

    /**
     * "My watermarked copies".
     * <p>
     * {@code isAuthenticated()} rather than {@code CAN_VIEW_DOCUMENTS},
     * matching {@code ExportJobController#listMine}: the list itself carries
     * no document content - only the caller's own job rows, which they
     * created. Losing document access still ends access to the copies,
     * because the DOWNLOAD re-checks it; gating the list as well would hide
     * a user's own history from them for no gain.
     */
    @GetMapping("/api/v1/watermark-jobs")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "List the caller's own watermark jobs",
            description = "Only jobs the caller requested, in the caller's tenant. status is optional "
                    + "(pending, processing, completed, failed, expired); anything else is a 400. "
                    + "Newest first by default.")
    public PageResponse<WatermarkJobResponse> listMine(
            @RequestParam(required = false) String status,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return PageResponse.from(watermarkJobService.listMine(status, pageable));
    }

    @GetMapping("/api/v1/watermark-jobs/{jobId}")
    @PreAuthorize(CAN_VIEW_DOCUMENTS)
    @Operation(summary = "One of the caller's own watermark jobs",
            description = "Only jobs the caller requested, in the caller's tenant. Poll this until "
                    + "downloadAvailable is true.")
    public WatermarkJobResponse get(@PathVariable UUID jobId) {
        return watermarkJobService.get(jobId);
    }

    /**
     * Re-checks the document, the caller's access to it and the expiry
     * before a byte is served - see {@code WatermarkJobService#download}.
     */
    @GetMapping("/api/v1/watermark-jobs/{jobId}/result")
    @PreAuthorize(CAN_VIEW_DOCUMENTS)
    @Operation(summary = "Download the finished watermarked copy",
            description = "In the document's own format. Access and expiry are re-checked at download, so "
                    + "losing access to the document also ends access to copies made from it.")
    public ResponseEntity<StreamingResponseBody> result(@PathVariable UUID jobId) {
        WatermarkJobService.DownloadedWatermark result = watermarkJobService.download(jobId);
        StreamingResponseBody body = out -> {
            try (InputStream content = result.content()) {
                content.transferTo(out);
            }
        };
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, result.contentType() == null
                        ? MediaType.APPLICATION_OCTET_STREAM_VALUE : result.contentType())
                .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(result.sizeBytes()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        // RFC 5987 encoding, so a non-ASCII title survives.
                        ContentDisposition.attachment().filename(result.filename()).build().toString())
                // The copy carries somebody's name and expires. A cached one
                // would outlive both the expiry and the access check.
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .body(body);
    }

    @PostMapping(value = "/api/v1/watermark-templates/preview", produces = MediaType.IMAGE_PNG_VALUE)
    @PreAuthorize(CAN_VIEW_DOCUMENTS + " or " + CAN_CONFIGURE)
    @Operation(summary = "Render a sample watermark as a PNG",
            description = "Placeholders are filled with sample values, not the caller's own. Creates "
                    + "nothing and reads no document.")
    public ResponseEntity<byte[]> preview(@Valid @RequestBody WatermarkPreviewRequest request,
                                          HttpServletRequest httpRequest) {
        rateLimiter.checkPreviewRequest(httpRequest);
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .body(previewService.render(request));
    }

    /**
     * The one refusal that comes with an answer to "what should I do then?".
     * Handled here rather than in {@code GlobalExceptionHandler} because the
     * {@code Retry-After} header is specific to this endpoint's contract -
     * a controller-local handler takes precedence and keeps the shared one
     * unchanged.
     */
    @ExceptionHandler(WatermarkQueueFullException.class)
    public ResponseEntity<ErrorResponse> queueFull(WatermarkQueueFullException e, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(Math.max(1, e.getRetryAfter().toSeconds())))
                .body(new ErrorResponse(OffsetDateTime.now(), HttpStatus.SERVICE_UNAVAILABLE.value(),
                        HttpStatus.SERVICE_UNAVAILABLE.getReasonPhrase(), e.getCode(), e.getMessage(),
                        request.getRequestURI(), null));
    }
}
