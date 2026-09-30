package com.oak.dms.scanning.helper;

import com.oak.dms.scanning.dto.DetectedFileType;
import com.oak.dms.scanning.exception.FileTypeNotAllowedException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The allowed-file-type check - the first thing the ingestion gate does,
 * before anything is staged or scanned (rejecting a disallowed type outright
 * is cheaper and safer than scanning it). Nothing in the upload path checked
 * types before this.
 * <p>
 * The extension must be in {@code dms.ingestion.allowed-file-types}, and the
 * file's first bytes must match that extension's format family - the
 * client's Content-Type is never trusted, and the canonical MIME type from
 * the table below is what gets stored. Works on a peek of the head of the
 * stream only; no second pass over the file.
 * <p>
 * Also detects the one encryption case the header alone proves: an OOXML
 * extension ({@code .docx/.xlsx/.pptx}) on an OLE2 compound file is how Office
 * stores a password-protected document. Encrypted PDFs are detected while
 * staging, by {@link EncryptionMarkerInputStream}.
 * <p>
 * <b>Archives are deliberately not accepted</b> - there is no zip/7z/rar/tar
 * entry in the table, and a ZIP container is only accepted when it is
 * structurally an OOXML or ODF document. This is a recorded deviation from
 * VMSBDI-FR-004 ("including embedded/compressed files (e.g., ZIP archives)"):
 * the system accepts no archive uploads, so there are no archives to scan -
 * see docs/scanning-architecture.md. (ClamAV still inspects the zip
 * structure inside OOXML/ODF files as part of its normal scan.)
 */
@Component
public class FileTypeInspector {

    /** How many head bytes the gate peeks at before staging. */
    public static final int HEAD_BYTES = 8192;

    private static final byte[] PDF = "%PDF-".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] ZIP = {'P', 'K', 3, 4};
    private static final byte[] OLE2 = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};
    private static final byte[] RTF = "{\\rtf".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] TIFF_LE = {'I', 'I', 42, 0};
    private static final byte[] TIFF_BE = {'M', 'M', 0, 42};
    private static final byte[] OOXML_MARKER = "[Content_Types].xml".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] ODF_MIMETYPE_ENTRY = "mimetype".getBytes(StandardCharsets.US_ASCII);
    /** Executable/binary signatures that must never pass as text. */
    private static final List<byte[]> NOT_TEXT = List.of(ZIP, OLE2, PDF, "MZ".getBytes(StandardCharsets.US_ASCII),
            new byte[]{0x7F, 'E', 'L', 'F'});

    private enum Family { PDF, OOXML, ODF, OLE2, RTF, TEXT, PNG, JPEG, TIFF }

    private record KnownType(String mimeType, Family family) {
    }

    private static final Map<String, KnownType> KNOWN_TYPES = new LinkedHashMap<>();

    static {
        KNOWN_TYPES.put("pdf", new KnownType("application/pdf", Family.PDF));
        KNOWN_TYPES.put("doc", new KnownType("application/msword", Family.OLE2));
        KNOWN_TYPES.put("xls", new KnownType("application/vnd.ms-excel", Family.OLE2));
        KNOWN_TYPES.put("ppt", new KnownType("application/vnd.ms-powerpoint", Family.OLE2));
        KNOWN_TYPES.put("docx", new KnownType(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", Family.OOXML));
        KNOWN_TYPES.put("xlsx", new KnownType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", Family.OOXML));
        KNOWN_TYPES.put("pptx", new KnownType(
                "application/vnd.openxmlformats-officedocument.presentationml.presentation", Family.OOXML));
        KNOWN_TYPES.put("odt", new KnownType("application/vnd.oasis.opendocument.text", Family.ODF));
        KNOWN_TYPES.put("ods", new KnownType("application/vnd.oasis.opendocument.spreadsheet", Family.ODF));
        KNOWN_TYPES.put("odp", new KnownType("application/vnd.oasis.opendocument.presentation", Family.ODF));
        KNOWN_TYPES.put("rtf", new KnownType("application/rtf", Family.RTF));
        KNOWN_TYPES.put("txt", new KnownType("text/plain", Family.TEXT));
        KNOWN_TYPES.put("csv", new KnownType("text/csv", Family.TEXT));
        KNOWN_TYPES.put("png", new KnownType("image/png", Family.PNG));
        KNOWN_TYPES.put("jpg", new KnownType("image/jpeg", Family.JPEG));
        KNOWN_TYPES.put("jpeg", new KnownType("image/jpeg", Family.JPEG));
        KNOWN_TYPES.put("tif", new KnownType("image/tiff", Family.TIFF));
        KNOWN_TYPES.put("tiff", new KnownType("image/tiff", Family.TIFF));
    }

    private final Set<String> allowedExtensions;

    public FileTypeInspector(IngestionProperties ingestionProperties) {
        List<String> configured = ingestionProperties.getAllowedFileTypes().stream()
                .map(ext -> ext.trim().toLowerCase(Locale.ROOT).replaceFirst("^\\.", ""))
                .filter(ext -> !ext.isEmpty())
                .toList();
        List<String> unknown = configured.stream().filter(ext -> !KNOWN_TYPES.containsKey(ext)).toList();
        if (!unknown.isEmpty()) {
            throw new IllegalStateException("dms.ingestion.allowed-file-types contains " + unknown
                    + ", which FileTypeInspector cannot verify by content - supported: " + KNOWN_TYPES.keySet());
        }
        this.allowedExtensions = Set.copyOf(configured);
    }

    /**
     * @param head the first {@code headLength} bytes of the upload (up to {@link #HEAD_BYTES})
     * @throws FileTypeNotAllowedException if the type isn't allowed or the content doesn't match the extension
     */
    public DetectedFileType inspect(String originalFilename, byte[] head, int headLength) {
        String extension = extensionOf(originalFilename);
        if (extension == null || !allowedExtensions.contains(extension)) {
            throw new FileTypeNotAllowedException("File type " + (extension == null ? "(none)" : "." + extension)
                    + " is not allowed. Allowed types: " + allowedExtensions.stream().sorted().toList());
        }
        if (headLength == 0) {
            throw new FileTypeNotAllowedException("The uploaded file is empty");
        }
        KnownType known = KNOWN_TYPES.get(extension);
        byte[] bytes = Arrays.copyOf(head, headLength);

        if (known.family() == Family.OOXML && startsWith(bytes, OLE2)) {
            // Password-protected OOXML: Office wraps the encrypted package in an OLE2 container.
            return new DetectedFileType(extension, known.mimeType(), false, true);
        }
        if (!matches(known.family(), bytes)) {
            throw new FileTypeNotAllowedException("The file's content does not match its ." + extension
                    + " extension");
        }
        return new DetectedFileType(extension, known.mimeType(), known.family() == Family.PDF, false);
    }

    private static boolean matches(Family family, byte[] head) {
        return switch (family) {
            // The PDF spec tolerates junk before the header; readers look in the first 1024 bytes.
            case PDF -> indexOf(head, PDF, 1024) >= 0;
            case OOXML -> startsWith(head, ZIP) && indexOf(head, OOXML_MARKER, head.length) >= 0;
            // ODF requires an uncompressed "mimetype" entry first: name at offset 30 of the first local header.
            case ODF -> startsWith(head, ZIP) && head.length >= 38
                    && Arrays.equals(head, 30, 38, ODF_MIMETYPE_ENTRY, 0, ODF_MIMETYPE_ENTRY.length);
            case OLE2 -> startsWith(head, OLE2);
            case RTF -> startsWith(head, RTF);
            case PNG -> startsWith(head, PNG);
            case JPEG -> startsWith(head, JPEG);
            case TIFF -> startsWith(head, TIFF_LE) || startsWith(head, TIFF_BE);
            case TEXT -> isText(head);
        };
    }

    private static boolean isText(byte[] head) {
        for (byte[] signature : NOT_TEXT) {
            if (startsWith(head, signature)) {
                return false;
            }
        }
        for (byte b : head) {
            if (b == 0) {
                return false;
            }
        }
        return true;
    }

    private static String extensionOf(String filename) {
        if (filename == null) {
            return null;
        }
        String name = filename.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) {
            return null;
        }
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        return data.length >= prefix.length && Arrays.equals(data, 0, prefix.length, prefix, 0, prefix.length);
    }

    private static int indexOf(byte[] data, byte[] needle, int searchLimit) {
        int last = Math.min(data.length, searchLimit + needle.length) - needle.length;
        for (int i = 0; i <= last; i++) {
            if (Arrays.equals(data, i, i + needle.length, needle, 0, needle.length)) {
                return i;
            }
        }
        return -1;
    }
}
