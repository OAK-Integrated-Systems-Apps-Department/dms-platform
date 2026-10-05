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
 * <b>ZIP is the one archive format accepted</b> ({@code .zip}, stored as
 * uploaded - never extracted, nothing in it run). Its head must be a ZIP
 * local file header, and a first entry flagged as encrypted marks it
 * encrypted here, like a password-protected Office file. The whole archive is
 * checked once staged ({@link ZipArchiveCheck}) and ClamAV scans every file
 * inside it. 7z/rar/tar/gz and the rest are still refused. A ZIP container
 * with an Office or OpenDocument extension must still be that document.
 */
@Component
public class FileTypeInspector {

    /** How many head bytes the gate peeks at before staging. */
    public static final int HEAD_BYTES = 8192;

    private static final byte[] PDF = "%PDF-".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] ZIP = {'P', 'K', 3, 4};
    /** An empty ZIP is only its end record - let ZipArchiveCheck say so in words. */
    private static final byte[] EMPTY_ZIP = {'P', 'K', 5, 6};
    private static final byte[] OLE2 = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};
    private static final byte[] RTF = "{\\rtf".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] TIFF_LE = {'I', 'I', 42, 0};
    private static final byte[] TIFF_BE = {'M', 'M', 0, 42};
    private static final byte[] GIF87 = "GIF87a".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] GIF89 = "GIF89a".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] BMP = {'B', 'M'};
    private static final byte[] RIFF = "RIFF".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] WEBP = "WEBP".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] FTYP = "ftyp".getBytes(StandardCharsets.US_ASCII);
    /** ISO-BMFF brands of HEIF/HEIC still images (not video). */
    private static final List<String> HEIF_BRANDS = List.of("heic", "heix", "heim", "heis", "mif1", "msf1");
    /** AutoCAD DWG files start "AC10" followed by a two-digit release (AC1015, AC1032, ...). */
    private static final byte[] DWG = "AC10".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] OOXML_MARKER = "[Content_Types].xml".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] ODF_MIMETYPE_ENTRY = "mimetype".getBytes(StandardCharsets.US_ASCII);
    /** Executable/binary signatures that must never pass as text. */
    private static final List<byte[]> NOT_TEXT = List.of(ZIP, OLE2, PDF, "MZ".getBytes(StandardCharsets.US_ASCII),
            new byte[]{0x7F, 'E', 'L', 'F'});

    private enum Family { PDF, OOXML, ODF, OLE2, RTF, TEXT, PNG, JPEG, TIFF, GIF, BMP, WEBP, HEIF, DWG, EMAIL, XML, JSON, ZIP }

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
        // Added with the bulk import work (2026-10): common business formats,
        // each still verified by content. Deliberately NOT added: anything
        // that can carry active content when opened (html/htm/svg - scripts;
        // docm/xlsm/pptm - macros; js/exe/bat/ps1), and archives other than ZIP.
        KNOWN_TYPES.put("msg", new KnownType("application/vnd.ms-outlook", Family.OLE2));
        KNOWN_TYPES.put("eml", new KnownType("message/rfc822", Family.EMAIL));
        KNOWN_TYPES.put("vsd", new KnownType("application/vnd.visio", Family.OLE2));
        KNOWN_TYPES.put("vsdx", new KnownType("application/vnd.ms-visio.drawing", Family.OOXML));
        KNOWN_TYPES.put("odg", new KnownType("application/vnd.oasis.opendocument.graphics", Family.ODF));
        KNOWN_TYPES.put("tsv", new KnownType("text/tab-separated-values", Family.TEXT));
        KNOWN_TYPES.put("md", new KnownType("text/markdown", Family.TEXT));
        KNOWN_TYPES.put("xml", new KnownType("application/xml", Family.XML));
        KNOWN_TYPES.put("json", new KnownType("application/json", Family.JSON));
        KNOWN_TYPES.put("gif", new KnownType("image/gif", Family.GIF));
        KNOWN_TYPES.put("bmp", new KnownType("image/bmp", Family.BMP));
        KNOWN_TYPES.put("webp", new KnownType("image/webp", Family.WEBP));
        KNOWN_TYPES.put("heic", new KnownType("image/heic", Family.HEIF));
        KNOWN_TYPES.put("heif", new KnownType("image/heif", Family.HEIF));
        KNOWN_TYPES.put("dwg", new KnownType("image/vnd.dwg", Family.DWG));
        // A ZIP archive, kept as one file (see the class comment).
        KNOWN_TYPES.put("zip", new KnownType("application/zip", Family.ZIP));
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
        if (known.family() == Family.ZIP && startsWith(bytes, ZIP) && bytes.length >= 8 && (bytes[6] & 1) != 0) {
            // General-purpose flag bit 0 of the first entry: encrypted (ZipCrypto or AES) - can't be scanned.
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
            case GIF -> startsWith(head, GIF87) || startsWith(head, GIF89);
            case BMP -> startsWith(head, BMP) && head.length >= 26;
            case WEBP -> startsWith(head, RIFF) && head.length >= 12 && Arrays.equals(head, 8, 12, WEBP, 0, 4);
            case HEIF -> head.length >= 12 && Arrays.equals(head, 4, 8, FTYP, 0, 4)
                    && HEIF_BRANDS.contains(new String(head, 8, 4, StandardCharsets.US_ASCII));
            case DWG -> startsWith(head, DWG) && head.length >= 6
                    && Character.isDigit(head[4]) && Character.isDigit(head[5]);
            // An email is text whose first line is a header ("Received:", "From:", ...).
            case EMAIL -> isText(head) && firstLine(head).matches("[!-9;-~]+:.*");
            case XML -> isText(head) && firstNonBlank(head) == '<';
            case JSON -> isText(head) && (firstNonBlank(head) == '{' || firstNonBlank(head) == '[');
            // A local file header - or the end record alone, an empty archive ZipArchiveCheck refuses.
            case ZIP -> (startsWith(head, ZIP) && head.length >= 30) || (startsWith(head, EMPTY_ZIP) && head.length >= 22);
        };
    }

    /** The first non-whitespace character after an optional UTF-8 BOM, or 0. */
    private static char firstNonBlank(byte[] head) {
        int i = head.length >= 3 && (head[0] & 0xFF) == 0xEF && (head[1] & 0xFF) == 0xBB && (head[2] & 0xFF) == 0xBF ? 3 : 0;
        for (; i < head.length; i++) {
            char c = (char) (head[i] & 0xFF);
            if (!Character.isWhitespace(c)) {
                return c;
            }
        }
        return 0;
    }

    private static String firstLine(byte[] head) {
        String text = new String(head, StandardCharsets.ISO_8859_1).stripLeading();
        int end = text.indexOf('\n');
        return (end >= 0 ? text.substring(0, end) : text).strip();
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
