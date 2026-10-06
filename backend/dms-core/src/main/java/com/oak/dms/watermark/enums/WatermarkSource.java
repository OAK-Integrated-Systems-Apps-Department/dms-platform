package com.oak.dms.watermark.enums;

/**
 * What asked for the watermark (watermark_jobs.source). Phase 1 has exactly
 * one: a person choosing "Download a watermarked copy".
 * <p>
 * It exists as a column from the start so the automatic sources that are out
 * of scope for Phase 1 - on upload, on view, on download, a sharing rule -
 * can be told apart from a deliberate request in the audit trail without a
 * migration that has to backfill every existing row.
 */
public enum WatermarkSource {
    USER_REQUEST
}
