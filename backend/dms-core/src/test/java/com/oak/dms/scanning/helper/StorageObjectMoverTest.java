package com.oak.dms.scanning.helper;

import com.oak.dms.scanning.helper.StorageObjectMover.Destination;
import com.oak.dms.scanning.helper.StorageObjectMover.StagedObjectChangedException;
import com.oak.dms.storage.dto.DownloadedObject;
import com.oak.dms.storage.dto.StoredObjectMetadata;
import com.oak.dms.storage.entity.StorageLocation;
import com.oak.dms.storage.enums.StorageProvider;
import com.oak.dms.storage.exception.StorageDeleteException;
import com.oak.dms.storage.exception.StorageDownloadException;
import com.oak.dms.storage.exception.StorageObjectNotFoundException;
import com.oak.dms.storage.exception.StorageUploadException;
import com.oak.dms.storage.service.ObjectStorageService;
import com.oak.dms.storage.service.ObjectStorageServiceFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DOC-13: what {@link StorageObjectMover#copyVerified} leaves behind when a copy fails. Uses two in-memory storages
 * (source and destination) that can be told to fail part way, so the exact objects left on each side are asserted.
 */
class StorageObjectMoverTest {

    private static final byte[] CONTENT = "the scanned bytes, long enough to fail half way through them".getBytes(StandardCharsets.UTF_8);
    private static final String SHA = sha256(CONTENT);
    private static final String FROM_KEY = "staging/t/1";

    private final StorageLocation stagingLocation = location();
    private final StorageLocation documentLocation = location();
    private FakeStorage staging;
    private FakeStorage documents;
    private StorageObjectMover mover;

    @BeforeEach
    void setUp() {
        staging = new FakeStorage();
        documents = new FakeStorage();
        staging.objects.put(FROM_KEY, CONTENT.clone());
        ObjectStorageServiceFactory factory = mock(ObjectStorageServiceFactory.class);
        when(factory.resolve(stagingLocation)).thenReturn(staging);
        when(factory.resolve(documentLocation)).thenReturn(documents);
        when(factory.resolveForInPlaceScan(documentLocation)).thenReturn(documents);
        mover = new StorageObjectMover(factory);
    }

    private StoredObjectMetadata copy(String toKey, Destination destination) {
        return mover.copyVerified(stagingLocation, FROM_KEY, documentLocation, toKey, CONTENT.length,
                "application/pdf", SHA, true, destination);
    }

    private void assertSourceIntact() {
        assertThat(staging.objects).containsOnlyKeys(FROM_KEY);
        assertThat(staging.objects.get(FROM_KEY)).isEqualTo(CONTENT);
        assertThat(staging.deleteCalls).as("the source is never deleted by a copy").isEmpty();
    }

    // ---- success ----

    @Test
    void aSuccessfulCopyLeavesTheVerifiedObjectAtTheDestinationAndTheSourceInPlace() {
        StoredObjectMetadata stored = copy("documents/a", Destination.NEW_UNREFERENCED);

        assertThat(stored.objectKey()).isEqualTo("documents/a");
        assertThat(documents.objects.get("documents/a")).isEqualTo(CONTENT);
        assertThat(documents.deleteCalls).isEmpty();
        assertSourceIntact();
    }

    // ---- upload failures ----

    @Test
    void anUploadThatFailsBeforeAnyByteIsWrittenLeavesNothingAndTheOriginalFailureIsRethrown() {
        StorageUploadException failure = new StorageUploadException("documents/a", new IOException("disk full"));
        documents.uploadThrows = failure;

        assertThatThrownBy(() -> copy("documents/a", Destination.NEW_UNREFERENCED)).isSameAs(failure);

        assertThat(documents.objects).isEmpty();
        assertThat(documents.deleteCalls).containsExactly("documents/a");
        assertSourceIntact();
    }

    @Test
    void anUploadThatFailsAfterPartialBytesHasItsPartialObjectRemoved() {
        documents.failAfterBytes = 20;

        assertThatThrownBy(() -> copy("documents/a", Destination.NEW_UNREFERENCED))
                .isInstanceOf(StorageUploadException.class);

        assertThat(documents.partialBytesWritten).isEqualTo(20);        // the failure really left something behind...
        assertThat(documents.objects).isEmpty();                         // ...and it was removed
        assertSourceIntact();
    }

    @Test
    void anUnexpectedRuntimeFailureInTheStorageAdapterIsCleanedUpToo() {
        IllegalStateException failure = new IllegalStateException("adapter bug");
        documents.uploadThrows = failure;

        assertThatThrownBy(() -> copy("documents/a", Destination.NEW_UNREFERENCED)).isSameAs(failure);

        assertThat(documents.deleteCalls).containsExactly("documents/a");
    }

    @Test
    void anErrorIsCleanedUpToo() {
        AssertionError failure = new AssertionError("out of something");
        documents.uploadError = failure;

        assertThatThrownBy(() -> copy("documents/a", Destination.NEW_UNREFERENCED)).isSameAs(failure);

        assertThat(documents.deleteCalls).containsExactly("documents/a");
    }

    // ---- source failures ----

    @Test
    void aSourceReadFailureMidStreamRemovesThePartialDestination() {
        staging.readFailsAfterBytes = 15;

        assertThatThrownBy(() -> copy("documents/a", Destination.NEW_UNREFERENCED))
                .isInstanceOf(StorageUploadException.class);

        assertThat(documents.partialBytesWritten).isEqualTo(15);
        assertThat(documents.objects).isEmpty();
        assertSourceIntact();
    }

    @Test
    void aSourceThatCannotBeOpenedNeverTouchesTheDestination() {
        staging.downloadThrows = new StorageDownloadException(FROM_KEY, new IOException("gone"));
        documents.objects.put("documents/other", new byte[]{1});

        assertThatThrownBy(() -> copy("documents/a", Destination.NEW_UNREFERENCED))
                .isInstanceOf(StorageDownloadException.class);

        assertThat(documents.deleteCalls).as("nothing was written, so nothing is removed").isEmpty();
        assertThat(documents.objects).containsOnlyKeys("documents/other");
    }

    // ---- verification ----

    @Test
    void aHashMismatchRemovesTheDestinationForAnyOwnership() {
        for (Destination destination : Destination.values()) {
            documents.objects.clear();
            documents.deleteCalls.clear();

            assertThatThrownBy(() -> mover.copyVerified(stagingLocation, FROM_KEY, documentLocation, "documents/a",
                    CONTENT.length, "application/pdf", sha256("something else".getBytes(StandardCharsets.UTF_8)), true,
                    destination)).isInstanceOf(StagedObjectChangedException.class);

            assertThat(documents.objects).as("destination after a mismatch (%s)", destination).isEmpty();
            assertThat(documents.deleteCalls).containsExactly("documents/a");
        }
        assertSourceIntact();
    }

    @Test
    void aMismatchWhoseCleanupFailsStillThrowsTheMismatchWithTheCleanupFailureAttached() {
        documents.deleteThrows = new StorageDeleteException("documents/a", new IOException("locked"));

        assertThatThrownBy(() -> mover.copyVerified(stagingLocation, FROM_KEY, documentLocation, "documents/a",
                CONTENT.length, "application/pdf", "00".repeat(32), true, Destination.NEW_UNREFERENCED))
                .isInstanceOf(StagedObjectChangedException.class)
                .hasSuppressedException(documents.deleteThrows);
    }

    // ---- cleanup failure ----

    @Test
    void whenCleanupAlsoFailsTheOriginalExceptionIsRethrownAndTheCleanupFailureIsSuppressed() {
        StorageUploadException failure = new StorageUploadException("documents/a", new IOException("disk full"));
        StorageDeleteException cleanup = new StorageDeleteException("documents/a", new IOException("locked"));
        documents.uploadThrows = failure;
        documents.deleteThrows = cleanup;

        assertThatThrownBy(() -> copy("documents/a", Destination.NEW_UNREFERENCED))
                .isSameAs(failure)
                .hasSuppressedException(cleanup);
        assertSourceIntact();
    }

    // ---- ownership ----

    @Test
    void aFailedCopyToASharedDestinationDoesNotDeleteWhatMayBeReferenced() {
        documents.objects.put("quarantine/t/1", "committed bytes".getBytes(StandardCharsets.UTF_8));
        documents.uploadThrows = new StorageUploadException("quarantine/t/1", new IOException("x"));

        assertThatThrownBy(() -> copy("quarantine/t/1", Destination.SHARED)).isInstanceOf(StorageUploadException.class);

        assertThat(documents.deleteCalls).isEmpty();
        assertThat(documents.objects).containsKey("quarantine/t/1");
    }

    @Test
    void theShorterOverloadsKeepTheSharedBehaviour() {
        documents.uploadThrows = new StorageUploadException("documents/a", new IOException("x"));

        assertThatThrownBy(() -> mover.copyVerified(stagingLocation, FROM_KEY, documentLocation, "documents/a",
                CONTENT.length, "application/pdf", SHA)).isInstanceOf(StorageUploadException.class);
        assertThatThrownBy(() -> mover.copyVerified(stagingLocation, FROM_KEY, documentLocation, "documents/a",
                CONTENT.length, "application/pdf", SHA, true)).isInstanceOf(StorageUploadException.class);

        assertThat(documents.deleteCalls).isEmpty();
    }

    @Test
    void aFailedAttemptNeverTouchesAnotherAttemptsObjectOrACommittedVersion() {
        byte[] committed = "a committed version".getBytes(StandardCharsets.UTF_8);
        documents.objects.put("documents/t/d/v1/committed", committed.clone());
        documents.objects.put("documents/t/d/v2/other-attempt", "another attempt, still running".getBytes(StandardCharsets.UTF_8));
        documents.failAfterBytes = 10;

        assertThatThrownBy(() -> copy("documents/t/d/v2/this-attempt", Destination.NEW_UNREFERENCED))
                .isInstanceOf(StorageUploadException.class);

        assertThat(documents.deleteCalls).containsExactly("documents/t/d/v2/this-attempt");
        assertThat(documents.objects).containsOnlyKeys("documents/t/d/v1/committed", "documents/t/d/v2/other-attempt");
        assertThat(documents.objects.get("documents/t/d/v1/committed")).isEqualTo(committed);
    }

    // ---- retry ----

    @Test
    void aRetryWithANewDestinationKeyLeavesExactlyTheSuccessfulObject() {
        documents.failAfterBytes = 20;
        assertThatThrownBy(() -> copy("documents/t/d/v2/attempt-1", Destination.NEW_UNREFERENCED))
                .isInstanceOf(StorageUploadException.class);
        documents.failAfterBytes = -1;

        copy("documents/t/d/v2/attempt-2", Destination.NEW_UNREFERENCED);

        assertThat(documents.objects).containsOnlyKeys("documents/t/d/v2/attempt-2");
        assertThat(documents.objects.get("documents/t/d/v2/attempt-2")).isEqualTo(CONTENT);
        assertSourceIntact();                       // the staged copy is still there for the retry
    }

    // ---- fakes ----

    private static StorageLocation location() {
        return StorageLocation.builder().storageLocationId(UUID.randomUUID()).build();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** In-memory storage that can fail part way through an upload or a read. */
    private static final class FakeStorage implements ObjectStorageService {
        final Map<String, byte[]> objects = new LinkedHashMap<>();
        final List<String> deleteCalls = new ArrayList<>();
        RuntimeException uploadThrows;
        Error uploadError;
        RuntimeException downloadThrows;
        RuntimeException deleteThrows;
        int failAfterBytes = -1;           // upload: keep this many bytes at the key, then fail
        int readFailsAfterBytes = -1;      // download: the returned stream fails after this many bytes
        int partialBytesWritten = -1;

        @Override
        public StorageProvider supportedProvider() {
            return StorageProvider.FILE_SYSTEM;
        }

        @Override
        public StoredObjectMetadata upload(StorageLocation location, String key, InputStream content, long length,
                                           String contentType) {
            if (uploadThrows != null) {
                throw uploadThrows;
            }
            if (uploadError != null) {
                throw uploadError;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[7];
            try {
                int n;
                while ((n = content.read(buffer)) != -1) {
                    out.write(buffer, 0, n);
                    if (failAfterBytes >= 0 && out.size() >= failAfterBytes) {
                        byte[] partial = out.toByteArray();
                        objects.put(key, java.util.Arrays.copyOf(partial, failAfterBytes));  // written straight to the key
                        partialBytesWritten = failAfterBytes;
                        throw new IOException("simulated write failure");
                    }
                }
            } catch (IOException e) {
                if (!objects.containsKey(key)) {
                    objects.put(key, out.toByteArray());
                    partialBytesWritten = out.size();
                }
                throw new StorageUploadException(key, e);
            }
            objects.put(key, out.toByteArray());
            return new StoredObjectMetadata(key, out.size(), contentType, null, OffsetDateTime.now());
        }

        @Override
        public DownloadedObject download(StorageLocation location, String key) {
            if (downloadThrows != null) {
                throw downloadThrows;
            }
            byte[] bytes = objects.get(key);
            if (bytes == null) {
                throw new StorageObjectNotFoundException(key);
            }
            InputStream in = new ByteArrayInputStream(bytes);
            if (readFailsAfterBytes >= 0) {
                in = new FailingInputStream(in, readFailsAfterBytes);
            }
            return new DownloadedObject(in, bytes.length, "application/octet-stream");
        }

        @Override
        public void delete(StorageLocation location, String key) {
            deleteCalls.add(key);
            if (deleteThrows != null) {
                throw deleteThrows;
            }
            objects.remove(key);
        }

        @Override
        public boolean exists(StorageLocation location, String key) {
            return objects.containsKey(key);
        }

        @Override
        public StoredObjectMetadata getMetadata(StorageLocation location, String key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<String> generatePresignedDownloadUrl(StorageLocation location, String key, Duration ttl) {
            return Optional.empty();
        }
    }

    private static final class FailingInputStream extends InputStream {
        private final InputStream in;
        private int left;

        FailingInputStream(InputStream in, int left) {
            this.in = in;
            this.left = left;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) == -1 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (left <= 0) {
                throw new IOException("simulated read failure");
            }
            int n = in.read(b, off, Math.min(len, left));
            if (n > 0) {
                left -= n;
            }
            return n;
        }
    }
}
