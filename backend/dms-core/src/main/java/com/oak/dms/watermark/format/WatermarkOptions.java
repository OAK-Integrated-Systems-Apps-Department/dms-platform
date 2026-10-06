package com.oak.dms.watermark.format;

import com.oak.dms.watermark.dto.WatermarkSpec;

/**
 * Everything a format service needs besides the bytes themselves.
 * <p>
 * {@code mimeType} is the canonical type from {@code document_versions},
 * re-verified against the actual bytes before this is built - never the
 * client's Content-Type and never guessed from the filename.
 * {@code originalFilename} is here only so a service can tell docx from
 * xlsx from pptx, which share one magic signature and one MIME family; it is
 * never used to decide whether a file is safe to open.
 */
public record WatermarkOptions(WatermarkSpec spec, String mimeType, String originalFilename) {
}
