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
 *   <li>{@code released_override} - a quarantine release attempt where an
 *   administrator force-released a file the scanner still flags (V40). Set in
 *   the same transaction as the {@code overridden} version it produced.</li>
 * </ul>
 * For a quarantine <em>release attempt</em> (a row with
 * {@code releasedFromScanResultId} set), {@code quarantined} means "re-scan
 * still detects the threat - release refused, the file stays under its
 * original quarantine record".
 */
public enum ScanResultStatus {
    pending,
    clean,
    infected,
    failed,
    unscannable,
    quarantined,
    released_override;

    public boolean isTerminal() {
        return this != pending && this != infected;
    }
}
