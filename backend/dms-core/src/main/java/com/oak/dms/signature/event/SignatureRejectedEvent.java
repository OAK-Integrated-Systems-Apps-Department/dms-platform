package com.oak.dms.signature.event;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A signature could not be recorded (wrong password, missing OTP,
 * impersonation, a tampered file, the key service down, or a failed
 * self-check) - published from {@code DocumentSignatureService#sign} right
 * before throwing, so {@code SignatureFailureRecorder}
 * ({@code @TransactionalEventListener(AFTER_ROLLBACK)}) can still write an
 * audit row even though the approval transaction this ran inside is about
 * to roll back and take any audit row written directly in it with it.
 */
public record SignatureRejectedEvent(
        UUID tenantId,
        UUID actorUserId,
        UUID documentId,
        UUID taskId,
        String code,
        String message,
        OffsetDateTime occurredAt
) {
}
