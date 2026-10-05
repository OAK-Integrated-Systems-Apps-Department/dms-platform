package com.oak.dms.scanning.helper;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

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
     * A ZIP may hold at most this many entries (ZipArchiveCheck) - clamd's MaxFiles, past which it stops
     * looking inside and the rest would go unscanned.
     */
    private int zipMaxEntries = 10_000;

    /** ...and come to at most this much unpacked - clamd's MaxScanSize (4000M). */
    private long zipMaxUncompressedBytes = 4_194_304_000L;
}
