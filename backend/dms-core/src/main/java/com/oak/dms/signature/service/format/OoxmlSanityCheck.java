package com.oak.dms.signature.service.format;

import com.oak.dms.signature.exception.SignatureException;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

/**
 * What every OOXML stamper (docx/xlsx/pptx) checks before POI is allowed near
 * the bytes: not password-protected, not already digitally signed (stamping
 * it would break that signature, same reasoning as {@code DocumentStampService
 * #refuseIfSigned} for PDF), and at least a readable zip package.
 * <p>
 * Deliberately NOT {@code com.oak.dms.watermark.format.OoxmlGuards}: that
 * class also enforces a zip-bomb/expansion quota tuned for arbitrary user
 * uploads at watermark time, is package-private to the watermark package, and
 * throws {@code WatermarkException}. A document being signed has already
 * passed that same scrutiny once, at upload/scan time (malware scanning,
 * {@code DocumentFingerprints}), so this only needs the cheap, format-
 * agnostic sanity checks - not a second copy of the quota logic.
 */
@Slf4j
final class OoxmlSanityCheck {

    private static final byte[] OLE2_SIGNATURE = {
            (byte) 0xD0, (byte) 0xCF, (byte) 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, (byte) 0x1A, (byte) 0xE1};
    private static final byte[] ZIP_SIGNATURE = {'P', 'K', 0x03, 0x04};
    private static final String SIGNATURE_PART = "_xmlsignatures/";

    private OoxmlSanityCheck() {
    }

    static void check(byte[] source, String what) {
        if (startsWith(source, OLE2_SIGNATURE)) {
            throw SignatureException.badRequest("SIGNATURE_PLACEMENT_OOXML_ENCRYPTED",
                    "This " + what + " is password-protected, so it can't be signed in place.");
        }
        if (!startsWith(source, ZIP_SIGNATURE)) {
            throw SignatureException.badRequest("SIGNATURE_PLACEMENT_OOXML_DAMAGED",
                    "This " + what + " could not be read - it may be damaged or incomplete.");
        }
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(source))) {
            ZipEntry entry;
            boolean any = false;
            while ((entry = zip.getNextEntry()) != null) {
                any = true;
                if (entry.getName().startsWith(SIGNATURE_PART)) {
                    throw SignatureException.conflict("SIGNATURE_PLACEMENT_OOXML_SIGNED",
                            "This " + what + " already carries its own digital signature. Stamping it would "
                                    + "break that signature, so a copy can't be produced.");
                }
            }
            if (!any) {
                throw SignatureException.badRequest("SIGNATURE_PLACEMENT_OOXML_DAMAGED",
                        "This " + what + " could not be read - it may be damaged or incomplete.");
            }
        } catch (ZipException e) {
            throw SignatureException.badRequest("SIGNATURE_PLACEMENT_OOXML_DAMAGED",
                    "This " + what + " could not be read - it may be damaged or incomplete.");
        } catch (IOException e) {
            log.debug("Could not walk an OOXML package for signature placement", e);
            throw SignatureException.badRequest("SIGNATURE_PLACEMENT_OOXML_DAMAGED",
                    "This " + what + " could not be read - it may be damaged or incomplete.");
        }
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
