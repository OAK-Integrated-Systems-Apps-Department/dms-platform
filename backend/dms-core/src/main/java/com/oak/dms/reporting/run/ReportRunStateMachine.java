package com.oak.dms.reporting.run;

import com.oak.dms.reporting.ReportException;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static com.oak.dms.reporting.run.ReportRunStatus.cancelled;
import static com.oak.dms.reporting.run.ReportRunStatus.completed;
import static com.oak.dms.reporting.run.ReportRunStatus.expired;
import static com.oak.dms.reporting.run.ReportRunStatus.failed;
import static com.oak.dms.reporting.run.ReportRunStatus.queued;
import static com.oak.dms.reporting.run.ReportRunStatus.running;
import static com.oak.dms.reporting.run.ReportRunStatus.skipped;

/**
 * The ONLY place that decides which run transitions are legal. Everything that changes a run's status asks here first, so a
 * second worker, a late cancel or a sweeper race cannot move a run somewhere it must not go.
 * <pre>
 *   queued  -> running | cancelled | skipped | failed
 *   running -> completed | failed | cancelled
 *   completed -> expired
 *   failed, cancelled, expired, skipped: final
 * </pre>
 */
@Component
public class ReportRunStateMachine {

    private static final Map<ReportRunStatus, Set<ReportRunStatus>> LEGAL = new EnumMap<>(ReportRunStatus.class);

    static {
        LEGAL.put(queued, EnumSet.of(running, cancelled, skipped, failed));
        LEGAL.put(running, EnumSet.of(completed, failed, cancelled));
        LEGAL.put(completed, EnumSet.of(expired));
        LEGAL.put(failed, EnumSet.noneOf(ReportRunStatus.class));
        LEGAL.put(cancelled, EnumSet.noneOf(ReportRunStatus.class));
        LEGAL.put(expired, EnumSet.noneOf(ReportRunStatus.class));
        LEGAL.put(skipped, EnumSet.noneOf(ReportRunStatus.class));
    }

    public boolean allows(ReportRunStatus from, ReportRunStatus to) {
        return LEGAL.get(from).contains(to);
    }

    /** @throws ReportException 409 {@code REPORT_RUN_STATE_INVALID} when the move is not legal */
    public ReportRunStatus transition(ReportRunStatus from, ReportRunStatus to) {
        if (!allows(from, to)) {
            throw ReportException.conflict("REPORT_RUN_STATE_INVALID", "A run that is " + from + " cannot become " + to + ".");
        }
        return to;
    }

    /** Cancel is allowed while the run is waiting or running (brief 2.3). */
    public boolean canCancel(ReportRunStatus status) {
        return allows(status, cancelled);
    }
}
