package com.oak.dms.ownership.controller;

import com.oak.dms.ownership.dto.OffboardRequest;
import com.oak.dms.ownership.dto.OffboardResponse;
import com.oak.dms.ownership.dto.OffboardingPreviewResponse;
import com.oak.dms.ownership.helper.OwnershipRetry;
import com.oak.dms.ownership.service.OffboardingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Offboarding: preview what a departing user leaves behind, then transfer their documents and deactivate them. */
@RestController
@RequestMapping("/api/v1/users/{userId}")
@RequiredArgsConstructor
@Tag(name = "Document ownership")
public class OffboardingController {

    private static final String P = "T(com.oak.dms.security.rbac.catalog.PermissionCodes).";
    private static final String SELECT = "@authz.hasPermission(" + P + "DOT_BULK_SELECT)";
    private static final String OFFBOARD = "@authz.hasPermission(" + P + "DOT_BULK_EXECUTE) and @authz.hasPermission("
            + P + "OTUA_USERS_DEACTIVATE)";

    private final OffboardingService service;
    private final OwnershipRetry retry;

    @GetMapping("/offboarding-preview")
    @PreAuthorize(SELECT)
    @Operation(summary = "What offboarding this user would involve",
            description = "Owned documents (visible / hidden / Recycle Bin), the suggested holding owner, and the open approval "
                    + "tasks and started runs that would be left behind.")
    public OffboardingPreviewResponse preview(@PathVariable("userId") UUID userId) {
        return service.preview(userId);
    }

    @PostMapping("/offboard")
    @PreAuthorize(OFFBOARD)
    @Operation(summary = "Transfer a user's documents and deactivate the account",
            description = "409 OFFBOARD_CONFIRMATION_REQUIRED (details = the flagged work) unless acknowledgedStuckTasks equals the number of open tasks.")
    public OffboardResponse offboard(@PathVariable("userId") UUID userId, @Valid @RequestBody OffboardRequest request) {
        return retry.run(() -> service.offboard(userId, request));
    }
}
