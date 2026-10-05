package com.oak.dms.workflow.dto;

import java.util.UUID;

public record UserRef(UUID userId, String displayName) {
}
