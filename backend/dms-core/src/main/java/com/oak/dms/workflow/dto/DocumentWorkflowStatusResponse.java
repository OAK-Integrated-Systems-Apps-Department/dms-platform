package com.oak.dms.workflow.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The newest workflow run of one document, for the badges in a list of
 * documents (My Documents). Only documents the caller can open are
 * answered for, and only those that have ever been submitted.
 */
public record DocumentWorkflowStatusResponse(UUID documentId, UUID instanceId, String status,
                                             OffsetDateTime startedAt, boolean versionChangedFlag) {
}
