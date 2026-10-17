package com.oak.dms.storage.enums;

/**
 * What a {@link com.oak.dms.storage.entity.StorageLocation} is for. Staging and
 * quarantine are ordinary storage locations with a different job, so they get
 * every {@link StorageProvider} for free - see
 * docs/scanning-architecture.md.
 * <ul>
 *   <li>{@code document} - where promoted (scanned, clean) document versions live.
 *   The only purpose any document read path ever resolves.</li>
 *   <li>{@code staging} - short-lived files that are not document versions.
 *   Two users now: an upload waiting to be scanned ({@code com.oak.dms.scanning}),
 *   and a watermarked copy waiting to be downloaded
 *   ({@code com.oak.dms.watermark}, under {@code {tenantId}/tmp/watermarks/},
 *   deleted on expiry by {@code WatermarkOutputSweeper}). It used to say
 *   "written and read only by com.oak.dms.scanning", which stopped being
 *   true when watermarking landed.</li>
 *   <li>{@code quarantine} - where infected files are isolated. Written only by
 *   {@code com.oak.dms.scanning}; nothing in Phase 1 reads it.</li>
 * </ul>
 * Lowercase to match the {@code ck_storage_locations_purpose} CHECK values, same
 * convention as {@code StorageLocation.Status}. Immutable once a row exists
 * (V39's {@code trg_storage_locations_purpose_immutable}).
 */
public enum StoragePurpose {
    document,
    staging,
    quarantine
}
