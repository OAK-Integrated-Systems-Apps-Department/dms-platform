package com.oak.dms.checkout;

import com.oak.dms.document.Document;
import com.oak.dms.document.DocumentContentReader;
import com.oak.dms.document.DocumentNotScannedException;
import com.oak.dms.document.DocumentVersionRepository;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.storage.exception.StorageException;
import com.oak.dms.signature.repository.DocumentSignatureRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.UUID;

/**
 * Keeps digital signatures intact, for EVERY file type: whatever edits a document - check-out, the online editor,
 * a save from the editor, a check-in - is refused while the version it would change carries a signature. A signature
 * is evidence that exactly these bytes were approved; an edit, even a one-character one, makes it a lie.
 *
 * <p>A version counts as signed when ANY of these holds:
 * <ol>
 *   <li>the DMS holds a VALID signature bound to it (a workflow approval or a standalone signing);</li>
 *   <li>it was produced by placing a visual signature mark ({@code created_reason = signed_copy}) - the mark is
 *       burned into the page, so editing the file could move or erase it before or after it is signed;</li>
 *   <li>the FILE itself contains a signature made elsewhere ({@link EmbeddedSignatureDetector}: PDF, Word, Excel,
 *       PowerPoint, OpenDocument).</li>
 * </ol>
 * (1) and (2) are cheap database checks and are re-asked on every save, so a document signed in the middle of an
 * editing session cannot be overwritten. (3) reads the file, so it is asked once when editing STARTS.
 *
 * <p>Watermarks need no rule here: the DMS never writes one into a stored version. A watermark exists only on a
 * derived copy (a watermark job's output), which is not a document and so cannot be opened for editing at all.
 *
 * <p>This is on top of, not instead of, the signature LOCK ({@code DocumentSignatureLockService}), which a
 * workflow approval sets and which already refuses new versions, moves and deletes.
 */
@Component
@RequiredArgsConstructor
public class SignedContentGuard {

    /** The same machine-readable code whichever of the three reasons applies, so the UI has one thing to handle. */
    public static final String CODE = "DOCUMENT_HAS_SIGNATURE";

    private final DocumentSignatureRepository signatureRepository;
    private final DocumentVersionRepository versionRepository;
    private final DocumentContentReader contentReader;

    /** (1) or (2) - the DMS's own records. Cheap enough to ask on every save. */
    public boolean protectedByRecord(Document document) {
        UUID versionId = currentVersionId(document);
        if (versionId == null) {
            return false;
        }
        UUID tenantId = document.getTenantId();
        boolean validSignature = signatureRepository
                .findByTenantIdAndDocumentIdOrderBySignedAtDesc(tenantId, document.getDocumentId()).stream()
                .anyMatch(s -> s.isValid() && versionId.equals(s.getVersionId()));
        if (validSignature) {
            return true;
        }
        return versionRepository
                .findByVersionIdAndDocumentIdAndTenantIdAndDeletedFalse(versionId, document.getDocumentId(), tenantId)
                .map(v -> "signed_copy".equals(v.getCreatedReason()))
                .orElse(false);
    }

    /** Before a save or a check-in: the DMS's own records only. */
    public void guardSaving(Document document) {
        if (protectedByRecord(document)) {
            throw signedByRecord(document);
        }
    }

    /** Before check-out or opening the editor: the records, AND whether the file itself carries a signature. */
    public void guardOpening(Document document) {
        guardSaving(document);
        if (fileCarriesSignature(document)) {
            throw CheckoutException.conflict(CODE, "\"" + document.getTitle() + "\" contains a digital signature inside "
                    + "the file itself, so it can't be checked out or edited here - any change would break the "
                    + "signature.");
        }
    }

    private boolean fileCarriesSignature(Document document) {
        UUID tenantId = TenantContext.getTenantId();
        try {
            String filename = contentReader.currentForEditing(tenantId, document.getDocumentId()).filename();
            // Cheap exit before reading a byte: only formats that can hold a signature are ever opened.
            if (!EmbeddedSignatureDetector.canHoldSignature(filename)) {
                return false;
            }
            try (InputStream content = contentReader.openCurrentForEditing(tenantId, document.getDocumentId()).content()) {
                return EmbeddedSignatureDetector.hasSignature(filename, content);
            }
        } catch (DocumentNotScannedException notClean) {
            // Not scanned clean yet: there is no readable content to protect, and it cannot be opened in the editor either.
            return false;
        } catch (StorageException unreadable) {
            // The file is missing or storage is down. We cannot show it is unsigned, and a signature is not something
            // to gamble on, so refuse - with a plain message, never the storage path the raw error carries.
            throw CheckoutException.unavailable("DOCUMENT_FILE_UNAVAILABLE", "\"" + document.getTitle()
                    + "\" can't be read right now, so it can't be checked out or edited safely. Try again shortly.");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static CheckoutException signedByRecord(Document document) {
        return CheckoutException.conflict(CODE, "\"" + document.getTitle() + "\" carries a digital signature on its "
                + "current version, so it can't be checked out or edited - changing it would break the signature. "
                + "Remove the signature first if the document really has to change.");
    }

    private static UUID currentVersionId(Document document) {
        return document.getCurrentVersionId() != null ? document.getCurrentVersionId() : document.getLatestVersionId();
    }
}
