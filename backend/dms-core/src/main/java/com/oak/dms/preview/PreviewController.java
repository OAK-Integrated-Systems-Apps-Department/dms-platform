package com.oak.dms.preview;

import com.oak.dms.common.dto.PageResponse;
import com.oak.dms.document.DocumentRenditionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Document preview and rendition management (DPRM): the organisation's preview security policy, and the list of
 * previews that could not be made.
 */
@RestController
@RequestMapping("/api/v1/preview")
@RequiredArgsConstructor
@Tag(name = "Document preview", description = "Preview security policy and rendition failures")
public class PreviewController {

    private static final String P = "T(com.oak.dms.security.rbac.catalog.PermissionCodes).";
    private static final String MANAGE = "@authz.hasAnyPermission(" + P + "DPRM_PREVIEW_RESTRICT, " + P + "DPRM_PREVIEW_WATERMARK)";
    private static final int MAX_PAGE_SIZE = 100;

    public record PolicyRequest(String print, String download, String copy) {
    }

    private final PreviewPolicyService policies;
    private final DocumentRenditionService renditions;

    /** Anyone who can open a preview needs this to know what it may offer - so it is not permission-gated. */
    @GetMapping("/policy")
    @Operation(summary = "What the caller's organisation allows from a preview: print, download (allow | watermark | block), copy (allow | block)")
    public PreviewPolicyService.Policy policy() {
        return policies.mine();
    }

    @PutMapping("/policy")
    @PreAuthorize(MANAGE)
    @Operation(summary = "Change the preview security policy", description = "Applies to every preview from now on. Audited.")
    public PreviewPolicyService.Policy savePolicy(@RequestBody PolicyRequest request) {
        return policies.save(new PreviewPolicyService.Policy(request.print(), request.download(), request.copy()));
    }

    @GetMapping("/failures")
    @PreAuthorize(MANAGE)
    @Operation(summary = "Versions whose preview or thumbnail could not be made, newest first - for administrator review")
    public PageResponse<DocumentRenditionService.FailureView> failures(
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return renditions.failures(PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), MAX_PAGE_SIZE)));
    }

    @PostMapping("/failures/{versionId}/retry")
    @PreAuthorize(MANAGE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Clear a version's failure and make its preview and thumbnail again, in the background")
    public void retry(@PathVariable("versionId") UUID versionId) {
        renditions.retry(versionId);
    }
}
