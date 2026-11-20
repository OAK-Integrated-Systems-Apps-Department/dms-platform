package com.oak.dms.reporting.run;

import java.util.UUID;

/**
 * A run reached the end of its work - completed, failed, or never started - and nobody is waiting on it any more. Published by the
 * worker for every run; delivery listens for the scheduled ones. Not published for a cancelled run or one still waiting its turn.
 */
public record ReportRunFinished(UUID runId) {
}
