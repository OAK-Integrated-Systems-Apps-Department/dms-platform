package com.oak.dms.scanning.dto;

/**
 * An upload's type as {@code FileTypeInspector} verified it from content.
 *
 * @param mimeType  canonical MIME from the allowlist - stored instead of the client's Content-Type
 * @param encrypted true when the header alone already proves the file is encrypted
 *                  (an OOXML extension on an OLE2 container - how Office stores a password-protected .docx/.xlsx/.pptx)
 */
public record DetectedFileType(
        String extension,
        String mimeType,
        boolean pdf,
        boolean encrypted
) {
}
