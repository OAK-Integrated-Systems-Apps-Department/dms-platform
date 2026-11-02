package com.oak.dms.ownership.controller;

import com.oak.dms.ownership.dto.BatchSummaryResponse;
import com.oak.dms.ownership.dto.BulkPreviewResponse;
import com.oak.dms.ownership.dto.BulkTransferRequest;
import com.oak.dms.ownership.dto.OwnedDocumentsResponse;
import com.oak.dms.ownership.helper.OwnershipRetry;
import com.oak.dms.ownership.service.OwnershipBatchService;
import com.oak.dms.ownership.service.OwnershipQueryService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Review and bulk transfer of owned documents. The review list needs the DOT select-documents permission; the
 * transfer needs execute-transfer. A tenant admin acts on anything; anyone else only on documents they can open,
 * owned by people in departments they head.
 */
@RestController
@RequestMapping("/api/v1/ownership")
@RequiredArgsConstructor
@Tag(name = "Document ownership")
public class OwnershipBatchController {

    private static final String P = "T(com.oak.dms.security.rbac.catalog.PermissionCodes).";
    private static final String SELECT = "@authz.hasPermission(" + P + "DOT_BULK_SELECT)";
    private static final String EXECUTE = "@authz.hasPermission(" + P + "DOT_BULK_EXECUTE)";
    private static final String SELECT_OR_EXECUTE = "@authz.hasAnyPermission(" + P + "DOT_BULK_SELECT, " + P + "DOT_BULK_EXECUTE)";

    private final OwnershipQueryService queryService;
    private final OwnershipBatchService batchService;
    private final OwnershipRetry retry;

    @GetMapping("/documents")
    @PreAuthorize(SELECT)
    @Operation(summary = "Documents a user or department owns",
            description = "Needs userId or departmentId. Recycle Bin documents are listed (inRecycleBin) and purged ones never. "
                    + "hiddenCount = documents the caller can't open (only an admin can transfer them); never their titles.")
    public OwnedDocumentsResponse owned(@RequestParam(value = "userId", required = false) UUID userId,
                                        @RequestParam(value = "departmentId", required = false) UUID departmentId,
                                        @RequestParam(value = "q", required = false) String q,
                                        @RequestParam(value = "state", required = false) String state,
                                        @RequestParam(value = "recycleBin", required = false) Boolean recycleBin,
                                        @RequestParam(value = "page", defaultValue = "0") int page,
                                        @RequestParam(value = "size", defaultValue = "50") int size) {
        return queryService.owned(userId, departmentId, q, state, recycleBin, page, size);
    }

    @GetMapping("/pending")
    @PreAuthorize(SELECT)
    @Operation(summary = "Documents waiting for a new owner",
            description = "Documents parked with a holding owner (pendingReason holding) and documents whose owner is a "
                    + "deactivated or deleted user (owner_deactivated). Same visibility rules as the owned-documents list.")
    public OwnedDocumentsResponse pending(@RequestParam(value = "departmentId", required = false) UUID departmentId,
                                          @RequestParam(value = "q", required = false) String q,
                                          @RequestParam(value = "page", defaultValue = "0") int page,
                                          @RequestParam(value = "size", defaultValue = "50") int size) {
        return queryService.pending(departmentId, q, page, size);
    }

    @PostMapping("/transfers")
    @PreAuthorize(EXECUTE)
    @Operation(summary = "Transfer many documents (all of a source, or a selection)",
            description = "One transaction. 422 OWNERSHIP_BATCH_TOO_LARGE above 5,000 documents (use limit and repeat); "
                    + "409 CONCURRENT_UPDATE if documents are busy - nothing changes then.")
    public BatchSummaryResponse transfer(@Valid @RequestBody BulkTransferRequest request) {
        return retry.run(() -> batchService.transfer(request));
    }

    @PostMapping("/transfers/preview")
    @PreAuthorize(EXECUTE)
    @Operation(summary = "What a bulk transfer would do, without doing it",
            description = "Counts of documents that move, are skipped, and would be switched to Restricted.")
    public BulkPreviewResponse preview(@Valid @RequestBody BulkTransferRequest request) {
        return batchService.preview(request);
    }

    @GetMapping("/batches/{batchId}")
    @PreAuthorize(SELECT_OR_EXECUTE)
    @Operation(summary = "A transfer run's summary", description = "Admins any batch; others only their own. 404 otherwise.")
    public BatchSummaryResponse batch(@PathVariable("batchId") UUID batchId) {
        return batchService.batch(batchId);
    }
}
