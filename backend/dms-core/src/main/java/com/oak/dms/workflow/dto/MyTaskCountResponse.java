package com.oak.dms.workflow.dto;

/** open/overdue: my task queue; requestsForMe: open information requests addressed to me. */
public record MyTaskCountResponse(long open, long overdue, long requestsForMe) {
}
