package com.oak.dms.importexport.enums;

/**
 * upload_sessions.status (ck_upload_sessions_status in V42). A bulk import
 * session goes pending -> uploading -> completed (every byte received) and
 * is then used by a batch; {@code quarantined} belongs to the table's other
 * (direct upload) use and is never set here.
 */
public enum UploadSessionStatus {
    pending,
    uploading,
    quarantined,
    completed,
    failed
}
