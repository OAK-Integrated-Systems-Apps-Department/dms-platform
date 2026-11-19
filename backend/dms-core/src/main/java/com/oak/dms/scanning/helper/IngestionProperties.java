package com.oak.dms.scanning.helper;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Binds {@code dms.ingestion.*}. {@code allowedFileTypes} is the
 * deployment-wide allowlist of file extensions the ingestion gate accepts -
 * each must be one {@link FileTypeInspector} knows how to verify by content
 * (magic bytes); an unknown extension fails startup rather than being
 * silently accepted unchecked.
 */
@Component
@ConfigurationProperties(prefix = "dms.ingestion")
@Getter
@Setter
public class IngestionProperties {

    private List<String> allowedFileTypes = new ArrayList<>(List.of(
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "odg",
            "rtf", "txt", "csv", "tsv", "md", "xml", "json", "msg", "eml", "vsd", "vsdx", "dwg",
            "png", "jpg", "jpeg", "gif", "bmp", "webp", "heic", "heif", "tif", "tiff", "zip"));

    /**
     * A ZIP may hold at most this many entries in total, counting those of any ZIPs nested inside it
     * (ZipArchiveCheck) - 10,000, clamd's MaxFiles. This limits what is <em>inside</em> a ZIP, never the size of
     * the file uploaded, and it applies to ZIPs only.
     */
    private int zipMaxEntries = 10_000;

    /**
     * ...and come to at most this much unpacked in total, nested archives included - 4000 MiB, clamd's
     * MaxScanSize. Like the entry limit it concerns a ZIP's contents, not the uploaded file's size.
     */
    private long zipMaxUncompressedBytes = 4_194_304_000L;

    /**
     * File extensions refused inside a ZIP (programs and scripts), even when the scan finds no malware.
     * Entries that start like a program are refused whatever they're called (ZipArchiveCheck).
     */
    private Set<String> zipBlockedExtensions = new HashSet<>(ZipArchiveCheck.DEFAULT_BLOCKED_EXTENSIONS);
}
