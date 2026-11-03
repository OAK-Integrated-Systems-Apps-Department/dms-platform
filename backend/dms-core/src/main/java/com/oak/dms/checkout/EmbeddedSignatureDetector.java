package com.oak.dms.checkout;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Whether a FILE already carries a cryptographic digital signature inside it - one made by someone else, in
 * Word, Acrobat, LibreOffice and so on, before it was ever uploaded. Any edit to such a file breaks that signature,
 * so it must not be opened for editing. (Signatures the DMS itself recorded are protected by their database rows
 * instead - see {@link SignedContentGuard}.)
 *
 * <p>Looks for each format's own marker, never for "does it look signed" by guesswork:
 * <ul>
 *   <li><b>PDF</b> - a signature dictionary carries a {@code /ByteRange}; nothing else uses that key. Signature
 *       dictionaries are written as plain objects (the spec forbids hiding {@code /Contents} in an object stream),
 *       so a raw scan finds them even in a compressed PDF.</li>
 *   <li><b>Word / Excel / PowerPoint</b> (OOXML) - a package part under {@code _xmlsignatures/}.</li>
 *   <li><b>OpenDocument</b> (odt, ods, odp, odg...) - {@code META-INF/documentsignatures.xml}.</li>
 * </ul>
 * Anything else (plain text, csv, rtf, legacy binary .doc/.xls/.ppt) cannot be told apart cheaply and is reported
 * as unsigned; those formats' embedded signatures are a known limit, listed in docs/checkout-design.md.
 *
 * <p>It only ever reads the stream it is given, to the end at most, and never executes or renders anything.
 */
public final class EmbeddedSignatureDetector {

    private static final Set<String> OOXML = Set.of("docx", "docm", "dotx", "dotm", "xlsx", "xlsm", "xltx", "xltm",
            "pptx", "pptm", "potx", "potm", "ppsx");
    private static final Set<String> ODF = Set.of("odt", "ods", "odp", "odg", "odf", "ott", "ots", "otp");

    private static final byte[] BYTE_RANGE = "/ByteRange".getBytes(StandardCharsets.US_ASCII);

    private EmbeddedSignatureDetector() {
    }

    /** Whether this file type is one that can carry a signature we know how to find - so callers can skip reading the rest. */
    public static boolean canHoldSignature(String filename) {
        String ext = extensionOf(filename);
        return ext != null && ("pdf".equals(ext) || OOXML.contains(ext) || ODF.contains(ext));
    }

    /** @param filename decides which format's marker to look for; the content is never trusted to say what it is */
    public static boolean hasSignature(String filename, InputStream content) throws IOException {
        String ext = extensionOf(filename);
        if (ext == null) {
            return false;
        }
        if ("pdf".equals(ext)) {
            return pdfHasByteRange(content);
        }
        if (OOXML.contains(ext)) {
            return zipHasEntry(content, name -> name.startsWith("_xmlsignatures/"));
        }
        if (ODF.contains(ext)) {
            return zipHasEntry(content, "META-INF/documentsignatures.xml"::equals);
        }
        return false;
    }

    private static boolean pdfHasByteRange(InputStream in) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        // Carry the last (marker length - 1) bytes into the next read so a marker split across two reads is still found.
        int carry = BYTE_RANGE.length - 1;
        byte[] window = new byte[buffer.length + carry];
        int kept = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            System.arraycopy(buffer, 0, window, kept, read);
            int total = kept + read;
            if (indexOf(window, total) >= 0) {
                return true;
            }
            kept = Math.min(carry, total);
            System.arraycopy(window, total - kept, window, 0, kept);
        }
        return false;
    }

    private static int indexOf(byte[] data, int length) {
        outer:
        for (int i = 0; i <= length - BYTE_RANGE.length; i++) {
            for (int j = 0; j < BYTE_RANGE.length; j++) {
                if (data[i + j] != BYTE_RANGE[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static boolean zipHasEntry(InputStream in, java.util.function.Predicate<String> match) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (match.test(entry.getName())) {
                    return true;
                }
            }
        } catch (java.util.zip.ZipException notAZip) {
            // Not a readable package: nothing can be said about signatures in it, and it will not open in the editor either.
            return false;
        }
        return false;
    }

    static String extensionOf(String filename) {
        if (filename == null) {
            return null;
        }
        int dot = filename.lastIndexOf('.');
        return dot < 0 || dot == filename.length() - 1 ? null : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
