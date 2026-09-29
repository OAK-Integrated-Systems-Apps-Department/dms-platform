package com.oak.dms.scanning.dto;

import java.util.UUID;

/**
 * Published by {@code IngestionProcessor} inside the transaction that
 * quarantines a newly detected threat (never for a refused release re-scan).
 * {@code ThreatNotificationListener} reacts only after that transaction
 * commits, so a notification can never roll back or delay the quarantine.
 */
public record ThreatQuarantinedEvent(
        UUID quarantineId,
        UUID scanResultId,
        UUID tenantId
) {
}
