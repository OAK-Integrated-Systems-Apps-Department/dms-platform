package com.oak.dms.scanning.helper;

import com.oak.dms.scanning.dto.IngestionTicketResponse;
import com.oak.dms.scanning.entity.ScanResult;
import com.oak.dms.scanning.enums.ScanResultStatus;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * {@link ScanResult} -> {@link IngestionTicketResponse}, including the
 * uploader-facing message. Hand-written rather than MapStruct: the message
 * and {@code retrying} flag are derived, and the document id comes from the
 * promoted version, not a column on this row.
 */
@Component
public class IngestionTicketMapper {

    public IngestionTicketResponse toTicket(ScanResult row, UUID documentId) {
        boolean retrying = row.getStatus() == ScanResultStatus.pending && row.getAttemptCount() > 0;
        boolean threat = row.getStatus() == ScanResultStatus.infected || row.getStatus() == ScanResultStatus.quarantined;
        return new IngestionTicketResponse(
                row.getScanResultId(),
                row.getStatus(),
                documentId,
                row.getDocumentVersionId(),
                row.getOriginalFilename(),
                row.getSizeBytes(),
                row.getFileHash(),
                threat ? row.getThreatName() : null,
                message(row.getStatus(), retrying, row.getThreatName(), row.getFailureReason()),
                retrying,
                row.getCreatedAt(),
                row.getCompletedAt());
    }

    private static String message(ScanResultStatus status, boolean retrying, String threatName, String reason) {
        return switch (status) {
            case pending -> retrying
                    ? "The scan service is temporarily unavailable. Your file is held and will be scanned automatically."
                    : "Your file is being scanned for malware.";
            case clean -> "The file passed the malware scan and has been added.";
            case released_override -> "The file was released from quarantine by an administrator.";
            case infected, quarantined -> "A threat was detected" + (threatName != null ? " (" + threatName + ")" : "")
                    + ". The file was blocked and quarantined.";
            // clamd stopped part-way (AlertExceedsMax) - an archive nested too deep or holding too much.
            case unscannable -> reason != null && reason.startsWith("Heuristics.Limits.Exceeded")
                    ? "This archive is nested too deeply or holds too much for the virus scanner to check in full, "
                    + "so it was not accepted. Unpack the archives inside it, or split it, and upload again."
                    : "Password-protected or encrypted files can't be scanned and are not accepted. "
                    + "Remove the protection and upload the file again.";
            case failed -> "The file could not be scanned and was not added. Please try uploading it again later.";
        };
    }
}
