package com.oak.dms.workflow.dto;

import java.util.UUID;

/** displayName resolves the user/role/department name; null if it no longer exists. */
public record ParticipantResponse(UUID participantId, String assigneeType, UUID assigneeId, String displayName,
                                  int displayOrder) {
}
