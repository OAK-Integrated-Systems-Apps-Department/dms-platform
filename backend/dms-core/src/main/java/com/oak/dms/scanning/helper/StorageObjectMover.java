package com.oak.dms.scanning.helper;

import com.oak.dms.storage.dto.DownloadedObject;
import com.oak.dms.storage.dto.StoredObjectMetadata;
import com.oak.dms.storage.entity.StorageLocation;
import com.oak.dms.storage.service.ObjectStorageService;
import com.oak.dms.storage.service.ObjectStorageServiceFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Copies an object between two storage locations - staging to document on a
 * clean verdict, staging to quarantine on an infected one - as
 * {@code download -> upload} through {@link ObjectStorageServiceFactory}, so
 * it works across any pair of providers without adding a copy operation to
 * {@link ObjectStorageService}. (Server-side copy within one provider is a
 * later optimisation.)
 * <p>
 * Re-hashes the bytes while copying and refuses the copy - deleting the
 * destination object - unless they match the SHA-256 recorded when the file
 * was staged: what lands in the document location is provably the same bytes
 * the engine scanned, even if the staged object was tampered with in between.
 * The source is not deleted here; the caller does that only after its
 * database transaction commits.
 * <p>
 * <b>A failed copy does not leave its destination behind (DOC-13).</b> Whatever
 * goes wrong once the destination may have been written - the upload throws
 * part way, the source read fails, the bytes do not match - the object at the
 * destination key is removed again and the original failure is rethrown, with a
 * failed removal attached to it as a suppressed exception. That removal is only
 * done for a destination the caller declares exclusively its own
 * ({@link Destination#NEW_UNREFERENCED}: a key generated for this one attempt, which nothing
 * refers to yet and no other attempt will ever use). A retry of a failed promotion
 * allocates a new key, so an unremoved partial object would never be revisited.
 * For a key that is not exclusive ({@link Destination#SHARED}, e.g. the deterministic
 * quarantine key a retry overwrites) the only removal is the long-standing one after
 * a hash mismatch.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StorageObjectMover {

    private final ObjectStorageServiceFactory objectStorageServiceFactory;

    /**
     * Who may rely on the destination key after a failed copy. Decided by the caller, which allocated the key.
     */
    public enum Destination {
        /**
         * A key generated for this one attempt: no document version, quarantine record or other attempt refers to
         * it and none ever will, so a failed copy removes whatever it left there.
         */
        NEW_UNREFERENCED,
        /** A key that may already be referenced or is reused by retries: only a hash mismatch removes the object. */
        SHARED
    }

    /** @return the copied object's metadata at the destination (its ETag is what a cloud scanner reports back) */
    public StoredObjectMetadata copyVerified(StorageLocation from, String fromKey, StorageLocation to, String toKey,
                                             long sizeBytes, String contentType, String expectedSha256) {
        return copyVerified(from, fromKey, to, toKey, sizeBytes, contentType, expectedSha256, true, Destination.SHARED);
    }

    /**
     * @param encryptTarget false only when a cloud scanner must read the copy in place (re-staging for an
     *                      AWS GuardDuty / Defender scan) - see ObjectStorageServiceFactory#resolveUnencrypted
     */
    public StoredObjectMetadata copyVerified(StorageLocation from, String fromKey, StorageLocation to, String toKey,
                                             long sizeBytes, String contentType, String expectedSha256,
                                             boolean encryptTarget) {
        return copyVerified(from, fromKey, to, toKey, sizeBytes, contentType, expectedSha256, encryptTarget,
                Destination.SHARED);
    }

    /**
     * @param destination whether the destination key is exclusively this attempt's (see the class comment)
     */
    public StoredObjectMetadata copyVerified(StorageLocation from, String fromKey, StorageLocation to, String toKey,
                                             long sizeBytes, String contentType, String expectedSha256,
                                             boolean encryptTarget, Destination destination) {
        ObjectStorageService target = encryptTarget ? objectStorageServiceFactory.resolve(to)
                : objectStorageServiceFactory.resolveForInPlaceScan(to);
        // Nothing is written to the destination until this succeeds, so a failure here has nothing to clean up.
        DownloadedObject source = objectStorageServiceFactory.resolve(from).download(from, fromKey);
        MessageDigest digest = sha256();
        try {
            StoredObjectMetadata stored = null;
            try (InputStream content = new DigestInputStream(source.content(), digest)) {
                stored = target.upload(to, toKey, content, sizeBytes, contentType);
            } catch (IOException e) {
                log.debug("Closing source stream for {} failed", fromKey, e);
            }
            String actual = HexFormat.of().formatHex(digest.digest());
            if (!actual.equalsIgnoreCase(expectedSha256)) {
                throw new StagedObjectChangedException(fromKey);
            }
            return stored;
        } catch (StagedObjectChangedException e) {
            // Always removed, as before DOC-13: these bytes must never be readable from the destination.
            removeFailedDestination(target, to, toKey, e);
            throw e;
        } catch (RuntimeException | Error e) {
            if (destination == Destination.NEW_UNREFERENCED) {
                removeFailedDestination(target, to, toKey, e);
            }
            throw e;
        }
    }

    /**
     * Best-effort removal of the object a failed copy may have left at {@code toKey}. Never throws: a failure to
     * remove is attached to {@code failure} (so the original exception is what the caller sees) and logged without
     * any document content. Through the encrypting wrapper this deletes the object and then its key record, in that
     * order, so a failed object delete leaves the key record in place for a later reconciliation.
     */
    private void removeFailedDestination(ObjectStorageService target, StorageLocation to, String toKey,
                                         Throwable failure) {
        try {
            target.delete(to, toKey);
        } catch (RuntimeException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
            log.warn("Could not remove the partial destination object {} at storage location {} after a failed copy: {}",
                    toKey, to.getStorageLocationId(), cleanupFailure.getClass().getSimpleName());
        }
    }

    /** Best-effort delete - a leftover staged object is swept up later by the staging purge. */
    public boolean deleteQuietly(StorageLocation location, String objectKey) {
        try {
            objectStorageServiceFactory.resolve(location).delete(location, objectKey);
            return true;
        } catch (RuntimeException e) {
            log.warn("Could not delete object {} at storage location {}: {}", objectKey,
                    location.getStorageLocationId(), e.getMessage());
            return false;
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available on this JVM", e);
        }
    }

    /** The staged bytes no longer hash to what was scanned. Never promoted; treated like any failed attempt. */
    public static class StagedObjectChangedException extends RuntimeException {
        public StagedObjectChangedException(String objectKey) {
            super("Staged object " + objectKey + " no longer matches the hash recorded when it was staged");
        }
    }
}
