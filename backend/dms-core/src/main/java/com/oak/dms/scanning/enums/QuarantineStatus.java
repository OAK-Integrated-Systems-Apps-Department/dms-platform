package com.oak.dms.scanning.enums;

/** {@code quarantine_records.status}. Release/delete are Phase 2 admin actions; Phase 1 only ever writes {@code held}. */
public enum QuarantineStatus {
    held,
    released,
    deleted
}
