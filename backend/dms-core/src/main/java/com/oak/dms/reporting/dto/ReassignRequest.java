package com.oak.dms.reporting.dto;

import java.util.UUID;

/** The person a schedule is handed to. They must be active, may run reports, and can see the schedule's template. */
public record ReassignRequest(UUID newOwnerUserId) {
}
