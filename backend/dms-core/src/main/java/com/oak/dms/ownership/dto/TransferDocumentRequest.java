package com.oak.dms.ownership.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** Move one document to a new owner. {@code grantAccess} defaults to true (D4). */
public record TransferDocumentRequest(
        @NotNull UUID newOwnerUserId,
        UUID newOwnerDepartmentId,
        Boolean grantAccess,
        @Size(max = 1000) String note) {

    public boolean grant() {
        return grantAccess == null || grantAccess;
    }
}
