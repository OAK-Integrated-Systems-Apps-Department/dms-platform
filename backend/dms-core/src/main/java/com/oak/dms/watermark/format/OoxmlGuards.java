package com.oak.dms.watermark.format;

import com.oak.dms.watermark.config.WatermarkProperties;
import com.oak.dms.watermark.exception.WatermarkException;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

/**
 * The checks every OOXML format runs before POI is allowed near the bytes,
 * and the one place that turns POI's exceptions into sentences a user can
 * act on.
 *
 * <h2>Why this runs before POI opens anything</h2>
 * {@code XWPFDocument}, {@code XSSFWorkbook} and {@code XMLSlideShow} all
 * parse eagerly: by the time one of them has thrown, it has already
 * inflated parts of the package into memory. A malicious or merely broken
 * file is cheapest to refuse while it is still a byte array, and the refusal
 * is the same for all three formats, so it lives here rather than three
 * times over.
 *
 * <h2>ZipSecureFile is deliberately NOT touched</h2>
 * POI's zip-bomb guard ({@code ZipSecureFile.MIN_INFLATE_RATIO},
 * {@code MAX_ENTRY_SIZE}, {@code MAX_FILE_COUNT}) is a set of JVM-WIDE
 * STATICS. Changing them from here would change them for
 * {@code DocumentFingerprints}, which opens every uploaded Office file, and
 * for the import templates - features this change has no business altering,
 * and which would then be governed by whatever the last watermark job
 * happened to set. They are also not safe to mutate per call: two format
 * threads would race on the same field.
 * <p>
 * POI's effective defaults in 5.5.1 are a minimum inflate ratio of 0.01
 * (so at most 100:1 expansion), a maximum entry size of 4 GB and a maximum
 * of 1000 entries. The ratio is already STRICTER than the 0.001 this
 * feature has in its own configuration, so applying our value globally
 * would have loosened the protection for everyone else - the opposite of
 * the intent. What POI's defaults do not bound tightly is TOTAL inflated
 * size: at 100:1 a 25 MB upload may still reach 2.5 GB, which is an
 * out-of-memory rather than a refusal.
 * <p>
 * So the guard here is a local one: a single bounded pass that counts the
 * bytes that actually come out of the inflater and stops the moment they
 * pass a cap. It never trusts the sizes declared in the zip headers, which
 * an attacker writes; it is thread-safe because it holds no state; and it
 * applies only to watermarking. The cost is one pass over a file that is
 * already capped at 10-25 MB per format.
 */
@Slf4j
final class OoxmlGuards {

    /** The OLE2 compound-document signature - what a password-protected OOXML file really is. */
    private static final byte[] OLE2_SIGNATURE = {
            (byte) 0xD0, (byte) 0xCF, (byte) 0x11, (byte) 0xE0,
            (byte) 0xA1, (byte) 0xB1, (byte) 0x1A, (byte) 0xE1};
    /** Local file header of any zip, and therefore of any intact OOXML package. */
    private static final byte[] ZIP_SIGNATURE = {'P', 'K', 0x03, 0x04};
    /** Where a package keeps its digital signatures. Present only if the file is signed. */
    private static final String SIGNATURE_PART = "_xmlsignatures/";

    private OoxmlGuards() {
    }

    /**
     * Everything that can be decided from the bytes alone, in the order that
     * gives the most useful message: what kind of file this really is, then
     * whether it is signed, then whether it is safe to inflate.
     */
    static void check(byte[] source, WatermarkProperties.Limits limits) {
        refuseIfPasswordProtected(source);
        refuseIfNotAPackage(source);
        inspectEntries(source, limits);
    }

    /**
     * An OOXML file with a password is not an encrypted zip - it is an OLE2
     * compound document with the real package stored inside it as an
     * encrypted stream. POI reports that as {@code EncryptedDocumentException}
     * or {@code OLE2NotOfficeXmlFileException} only after a parse attempt,
     * and the wording of both is about file formats rather than about the
     * password, so the signature is checked here instead.
     */
    private static void refuseIfPasswordProtected(byte[] source) {
        if (startsWith(source, OLE2_SIGNATURE)) {
            throw WatermarkException.unprocessable("WATERMARK_OOXML_ENCRYPTED",
                    "This file is password-protected, so it can't be opened to add a watermark.");
        }
    }

    private static void refuseIfNotAPackage(byte[] source) {
        if (!startsWith(source, ZIP_SIGNATURE)) {
            throw WatermarkException.unprocessable("WATERMARK_OOXML_DAMAGED",
                    "This file could not be read. It may be damaged or incomplete.");
        }
    }

    /**
     * One pass over the package: refuses a signed file, and refuses one that
     * inflates to more than it should.
     * <p>
     * The totals come from what the inflater actually produces, counted as
     * it is produced and abandoned at the cap - never from
     * {@link ZipEntry#getSize()}, which is a number the file itself claims
     * and a bomb simply lies about.
     */
    private static void inspectEntries(byte[] source, WatermarkProperties.Limits limits) {
        long maxEntry = limits.getMaxZipEntrySize().toBytes();
        long maxTotal = Math.min((long) (source.length / Math.max(0.0001d, limits.getMinInflateRatio())),
                limits.getMaxUncompressedBytes().toBytes());
        int maxEntries = limits.getMaxZipEntries();

        long total = 0;
        int entries = 0;
        byte[] scratch = new byte[16 * 1024];

        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(source))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.getName().startsWith(SIGNATURE_PART)) {
                    throw WatermarkException.unprocessable("WATERMARK_OOXML_SIGNED",
                            "This document is digitally signed. Adding a watermark would break the "
                                    + "signature, so the copy can't be produced.");
                }
                if (++entries > maxEntries) {
                    throw WatermarkException.unprocessable("WATERMARK_OOXML_TOO_MANY_PARTS",
                            "This file contains far more internal parts than a document should, so it "
                                    + "hasn't been opened.");
                }
                long entryBytes = 0;
                int read;
                while ((read = zip.read(scratch)) > 0) {
                    entryBytes += read;
                    total += read;
                    if (entryBytes > maxEntry || total > maxTotal) {
                        log.warn("Refusing an OOXML package: {} stored bytes expanded past the cap "
                                        + "(entry {} bytes, total {} bytes)",
                                source.length, entryBytes, total);
                        throw WatermarkException.unprocessable("WATERMARK_OOXML_EXPANDS_TOO_FAR",
                                "This file expands to far more than its size suggests, so it hasn't "
                                        + "been opened.");
                    }
                }
            }
        } catch (ZipException e) {
            throw WatermarkException.unprocessable("WATERMARK_OOXML_DAMAGED",
                    "This file could not be read. It may be damaged or incomplete.");
        } catch (IOException e) {
            // A byte array can't fail to read for a disk reason, so this is
            // a truncated or malformed package rather than an environment
            // problem - the same answer as a bad zip.
            log.debug("Could not walk an OOXML package", e);
            throw WatermarkException.unprocessable("WATERMARK_OOXML_DAMAGED",
                    "This file could not be read. It may be damaged or incomplete.");
        }

        if (entries == 0) {
            throw WatermarkException.unprocessable("WATERMARK_OOXML_DAMAGED",
                    "This file could not be read. It may be damaged or incomplete.");
        }
    }

    /**
     * Turns whatever POI threw while opening or saving into a 422 with a
     * sentence for the requester.
     * <p>
     * Deliberately catch-all: POI reports a malformed package as any of
     * {@code POIXMLException}, {@code NotOfficeXmlFileException},
     * {@code XmlException}, a bare {@code IllegalStateException} or an
     * {@code IOException} depending on which part failed and how. Mapping
     * them one by one invites the one that was missed to escape as a
     * generic failed job, which tells the user nothing. The real exception
     * is logged; only the category reaches the caller.
     */
    static WatermarkException unreadable(String what, RuntimeException cause) {
        // WARN with the cause attached, not debug. This is the only record
        // of WHY a document was refused: the message the user gets says
        // "damaged", which is a guess, and the exception is swallowed here
        // so nothing downstream can log it. Hiding it at debug meant a
        // failed job could not be explained without a code change - which
        // is exactly what happened the first time these services ran.
        log.warn("Could not process a {} for watermarking - refusing it", what, cause);
        return WatermarkException.unprocessable("WATERMARK_OOXML_DAMAGED",
                "This " + what + " could not be read. It may be damaged or in a format this server "
                        + "doesn't recognise.");
    }

    private static boolean startsWith(byte[] source, byte[] signature) {
        if (source.length < signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if (source[i] != signature[i]) {
                return false;
            }
        }
        return true;
    }
}
