package com.oak.dms.scanning.enums;

/** What the ingestion gate creates once a file is clean - persisted on {@code scan_results} so async promotion knows. */
public enum IngestionKind {
    /** A new {@code Document} and its first version. */
    new_document,
    /** A new version of an existing {@code Document}. */
    new_version
}
