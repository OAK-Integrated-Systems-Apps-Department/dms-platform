package com.oak.dms.importexport.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** Starts a resumable upload of one ZIP for a bulk import. */
public record CreateUploadSessionRequest(
        @NotBlank @Size(max = 500) String fileName,
        @Positive long totalSizeBytes
) {
}
