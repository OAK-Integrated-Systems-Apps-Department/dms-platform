package com.oak.dms.workflow.dto;

import java.util.UUID;

/** One row of a people picker (delegate, request target, @mention). */
public record PersonOptionResponse(UUID userId, String displayName, String username, String departmentName,
                                   Boolean hasDocumentAccess) {
}
