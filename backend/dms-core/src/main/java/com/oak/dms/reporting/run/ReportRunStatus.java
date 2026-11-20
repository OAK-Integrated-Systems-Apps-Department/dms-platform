package com.oak.dms.reporting.run;

/** The states of a report run - the same words the {@code ck_report_runs_status} CHECK allows. */
public enum ReportRunStatus {
    queued, running, completed, failed, cancelled, expired, skipped;

    /** Nothing more will happen to a run in this state except its file expiring. */
    public boolean finished() {
        return this != queued && this != running;
    }
}
