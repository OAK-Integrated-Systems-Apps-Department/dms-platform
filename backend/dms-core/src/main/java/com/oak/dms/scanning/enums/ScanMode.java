package com.oak.dms.scanning.enums;

/**
 * How an engine gets at the bytes. A persisted column (on both
 * {@code scan_engines} and {@code scan_results}), not an adapter detail: a
 * cloud scanner physically cannot scan before the bytes land in its own
 * storage, which is why staging exists at all.
 * Lowercase to match the {@code ck_scan_engines_scan_mode} CHECK values.
 */
public enum ScanMode {
    /** The engine scans a stream and answers within the call (ClamAV). */
    pre_store_stream,
    /** The engine scans an object after it is written; the verdict arrives later (Defender, GuardDuty). */
    post_store_object
}
