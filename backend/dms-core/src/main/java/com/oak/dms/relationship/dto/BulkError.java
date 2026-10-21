package com.oak.dms.relationship.dto;

import java.util.UUID;

/**
 * One reason a bulk request was refused. A document that is missing, in another tenant or that the
 * caller can't open is always {@code NOT_FOUND_OR_NO_ACCESS} - the same answer for all three.
 */
public record BulkError(UUID documentId, String code, String message) {
}
