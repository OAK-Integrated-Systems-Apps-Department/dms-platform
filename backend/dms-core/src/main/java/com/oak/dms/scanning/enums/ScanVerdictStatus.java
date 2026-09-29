package com.oak.dms.scanning.enums;

/** What one {@link com.oak.dms.scanning.service.ScanService#scan} call concluded. Engine failures are exceptions, not verdicts. */
public enum ScanVerdictStatus {
    CLEAN,
    INFECTED,
    UNSCANNABLE,
    /** The provider has no verdict yet (cloud scanners only) - ask again later. */
    PENDING
}
