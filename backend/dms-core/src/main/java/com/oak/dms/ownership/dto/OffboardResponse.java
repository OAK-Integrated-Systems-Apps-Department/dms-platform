package com.oak.dms.ownership.dto;

public record OffboardResponse(BatchSummaryResponse batch, WorkFlags flags, boolean deactivated) {
}
