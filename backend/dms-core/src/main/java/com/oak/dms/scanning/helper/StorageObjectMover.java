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
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StorageObjectMover {

    private final ObjectStorageServiceFactory objectStorageServiceFactory;

    /** @return the copied object's metadata at the destination (its ETag is what a cloud scanner reports back) */
    public StoredObjectMetadata copyVerified(StorageLocation from, String fromKey, StorageLocation to, String toKey,
                                             long sizeBytes, String contentType, String expectedSha256) {
        ObjectStorageService target = objectStorageServiceFactory.resolve(to);
        DownloadedObject source = objectStorageServiceFactory.resolve(from).download(from, fromKey);
        MessageDigest digest = sha256();
        StoredObjectMetadata stored = null;
        try (InputStream content = new DigestInputStream(source.content(), digest)) {
            stored = target.upload(to, toKey, content, sizeBytes, contentType);
        } catch (IOException e) {
            log.debug("Closing source stream for {} failed", fromKey, e);
        }
        String actual = HexFormat.of().formatHex(digest.digest());
        if (!actual.equalsIgnoreCase(expectedSha256)) {
            target.delete(to, toKey);
            throw new StagedObjectChangedException(fromKey);
        }
        return stored;
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
