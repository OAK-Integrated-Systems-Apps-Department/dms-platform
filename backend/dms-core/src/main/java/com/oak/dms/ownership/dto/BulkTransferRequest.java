package com.oak.dms.ownership.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Transfer many documents at once. {@code mode "all"} takes every live document of the source user or
 * department (minus {@code excludeDocumentIds}); {@code "selected"} takes {@code documentIds}. More than 5,000
 * is refused; {@code limit} (lowest ids first) lets a caller split a larger set into repeated requests.
 */
public record BulkTransferRequest(
        UUID sourceUserId,
        UUID sourceDepartmentId,
        @NotNull String mode,
        List<UUID> documentIds,
        List<UUID> excludeDocumentIds,
        Integer limit,
        @NotNull @Valid TargetSpec target,
        String reason,
        Boolean grantAccess,
        @Size(max = 1000) String note) {

    public boolean grant() {
        return grantAccess == null || grantAccess;
    }

    /** Where the documents go: a named user (optionally under a department) or a department's holding owner. */
    public record TargetSpec(@NotNull String type, UUID newOwnerUserId, UUID newOwnerDepartmentId, UUID departmentId) {
    }
}
