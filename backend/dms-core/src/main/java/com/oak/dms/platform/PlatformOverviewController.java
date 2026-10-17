package com.oak.dms.platform;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The platform dashboard's data: every organisation on this installation and
 * what each is using.
 * <p>
 * Works WITHOUT tenant context, deliberately - a platform administrator
 * belongs to no tenant, so {@code GET /api/v1/dashboard} answers
 * 401 TENANT_CONTEXT_MISSING for them and there was previously nothing for
 * them to land on. {@code hasRole('PLATFORM_ADMIN')} rather than a
 * permission code, matching every other cross-tenant endpoint
 * ({@code TenantController}, {@code PlatformAuditEventController}): the
 * permission catalogue is tenant-scoped, so a catalogue entry could be
 * granted inside one organisation and would be the wrong gate for data
 * spanning all of them.
 */
@RestController
@RequestMapping("/api/v1/platform")
@RequiredArgsConstructor
@Tag(name = "Platform", description = "Cross-tenant views for platform administrators")
public class PlatformOverviewController {

    private final PlatformOverviewService platformOverviewService;

    @GetMapping("/overview")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @Operation(summary = "Every organisation with its document, storage and user counts, plus platform totals",
            description = "Counts and sizes only - never document titles, folder names or who did what. "
                    + "Answers how much of the system each client is using, without exposing what they "
                    + "store in it.")
    public PlatformOverviewResponse overview() {
        return platformOverviewService.overview();
    }

    @GetMapping("/organisations/{tenantId}/breakdown")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @Operation(summary = "One organisation's storage split by file family and its last twelve months",
            description = "Fetched when a dashboard row is opened, rather than for every organisation "
                    + "up front. Same limits as the overview: file families and counts, never a "
                    + "filename, document title or folder name.")
    public PlatformOrganisationBreakdown breakdown(@PathVariable java.util.UUID tenantId) {
        return platformOverviewService.breakdown(tenantId);
    }
}
