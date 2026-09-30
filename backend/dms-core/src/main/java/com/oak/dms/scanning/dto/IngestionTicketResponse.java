package com.oak.dms.scanning.dto;

import com.oak.dms.scanning.enums.ScanResultStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * What an upload returns, uniformly across scan providers: the scan result
 * is the ticket. {@code documentId}/{@code versionId} are only set once the
 * file is clean and promoted - with ClamAV that is before the upload request
 * returns; with a cloud scanner the caller polls
 * {@code GET /api/v1/ingestion-tickets/{scanResultId}}. Never exposes a
 * storage location or object key.
 *
 * @param retrying true while the file is held pending because the scan engine was unavailable
 */
public record IngestionTicketResponse(
        UUID scanResultId,
        ScanResultStatus status,
        UUID documentId,
        UUID versionId,
        String originalFilename,
        Long sizeBytes,
        String fileHash,
        String threatName,
        String message,
        boolean retrying,
        OffsetDateTime submittedAt,
        OffsetDateTime completedAt
) {
}
