package com.oak.dms.scanning.enums;

/**
 * {@code scan_results.status}. Lowercase to match {@code ck_scan_results_status}.
 * <ul>
 *   <li>{@code pending} - staged (or staging), awaiting a verdict or a retry.</li>
 *   <li>{@code clean} - promoted: set in the same transaction as the
 *   {@code DocumentVersion} it produced, never before.</li>
 *   <li>{@code infected} - threat found, not yet isolated (quarantine copy
 *   still to do - retried by the scheduler).</li>
 *   <li>{@code quarantined} - isolated, with a {@code quarantine_records} row.</li>
 *   <li>{@code unscannable} - encrypted/password-protected; rejected.</li>
 *   <li>{@code failed} - retries exhausted or upload interrupted; never promoted.</li>
 * </ul>
 */
public enum ScanResultStatus {
    pending,
    clean,
    infected,
    failed,
    unscannable,
    quarantined;

    public boolean isTerminal() {
        return this != pending && this != infected;
    }
}
