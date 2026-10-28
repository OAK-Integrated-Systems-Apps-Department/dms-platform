package com.oak.dms.encryption;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oak.dms.storage.dto.DownloadedObject;
import com.oak.dms.storage.dto.StoredObjectMetadata;
import com.oak.dms.storage.entity.StorageLocation;
import com.oak.dms.storage.enums.StorageProvider;
import com.oak.dms.storage.exception.StorageUploadException;
import com.oak.dms.storage.service.ObjectStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Encryption at rest around a storage adapter: only ciphertext stored, plaintext back, and the edge cases. */
class EncryptingObjectStorageServiceTest {

    @TempDir
    Path dir;

    private final MemoryStorage storage = new MemoryStorage();
    private final Map<String, StorageObjectKey> keyRows = new HashMap<>();
    private final EncryptionProperties properties = new EncryptionProperties();
    private final StorageLocation location = StorageLocation.builder().storageLocationId(UUID.randomUUID())
            .provider(StorageProvider.FILE_SYSTEM).build();
    private EncryptingObjectStorageService service;
    private LocalDevKeyService keys;

    @BeforeEach
    void setUp() {
        StorageObjectKeyRepository repository = mock(StorageObjectKeyRepository.class);
        when(repository.findByStorageLocationIdAndObjectKey(any(), anyString()))
                .thenAnswer(call -> Optional.ofNullable(keyRows.get(call.<String>getArgument(1))));
        when(repository.saveAndFlush(any())).thenAnswer(call -> {
            StorageObjectKey row = call.getArgument(0);
            keyRows.put(row.getObjectKey(), row);
            return row;
        });
        when(repository.deleteObject(any(), anyString()))
                .thenAnswer(call -> keyRows.remove(call.<String>getArgument(1)) == null ? 0 : 1);
        keys = new LocalDevKeyService(dir.resolve("keys.json"), new ObjectMapper(), true);
        service = new EncryptingObjectStorageService(storage, new KeyServices(keys), new ObjectKeyRecords(repository),
                new ObjectCipher(4096), properties, new DataKeyCache(properties));
    }

    @Test
    void storesOnlyCiphertext_andGivesThePlaintextBack() throws IOException {
        byte[] plaintext = "Board minutes - CONFIDENTIAL".repeat(400).getBytes(StandardCharsets.UTF_8);

        StoredObjectMetadata stored = service.upload(location, "docs/minutes.pdf", new ByteArrayInputStream(plaintext),
                plaintext.length, "application/pdf");

        assertThat(stored.sizeBytes()).isEqualTo(plaintext.length);
        byte[] raw = storage.objects.get("docs/minutes.pdf");
        assertThat(raw).isNotEqualTo(plaintext);
        assertThat(new String(raw, StandardCharsets.ISO_8859_1)).doesNotContain("CONFIDENTIAL");
        assertThat(storage.contentTypes.get("docs/minutes.pdf")).isEqualTo("application/octet-stream");
        assertThat(keyRows.get("docs/minutes.pdf").getKeyVersion()).isEqualTo(1);

        DownloadedObject back = service.download(location, "docs/minutes.pdf");
        try (InputStream in = back.content()) {
            assertThat(in.readAllBytes()).isEqualTo(plaintext);
        }
        assertThat(back.sizeBytes()).isEqualTo(plaintext.length);
        assertThat(service.getMetadata(location, "docs/minutes.pdf").sizeBytes()).isEqualTo(plaintext.length);
        assertThat(service.generatePresignedDownloadUrl(location, "docs/minutes.pdf", Duration.ofMinutes(5))).isEmpty();
    }

    @Test
    void anObjectStoredBeforeEncryption_passesThroughUnchanged() throws IOException {
        storage.objects.put("legacy.txt", "old plain file".getBytes(StandardCharsets.UTF_8));

        try (InputStream in = service.download(location, "legacy.txt").content()) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("old plain file");
        }
    }

    @Test
    void aFailedUpload_leavesNoKeyBehind_andDeleteRemovesTheKey() {
        storage.failNextUpload = true;
        assertThatThrownBy(() -> service.upload(location, "x.pdf", new ByteArrayInputStream(new byte[10]), 10, null))
                .isInstanceOf(StorageUploadException.class);
        assertThat(keyRows).doesNotContainKey("x.pdf");

        service.upload(location, "y.pdf", new ByteArrayInputStream(new byte[10]), 10, null);
        service.delete(location, "y.pdf");
        assertThat(keyRows).doesNotContainKey("y.pdf");
        assertThat(storage.objects).doesNotContainKey("y.pdf");
    }

    @Test
    void anUnknownLength_isStillEncryptedWithTheRightSize() throws IOException {
        byte[] plaintext = new byte[10_000];
        service.upload(location, "unknown.bin", new ByteArrayInputStream(plaintext), -1, null);

        assertThat(keyRows.get("unknown.bin").getPlaintextSize()).isEqualTo(10_000);
        try (InputStream in = service.download(location, "unknown.bin").content()) {
            assertThat(in.readAllBytes()).isEqualTo(plaintext);
        }
    }

    @Test
    void turnedOff_newObjectsAreStoredPlain_butEncryptedOnesStillDecrypt() throws IOException {
        service.upload(location, "before.txt", new ByteArrayInputStream("secret".getBytes()), 6, null);
        properties.setEnabled(false);

        service.upload(location, "after.txt", new ByteArrayInputStream("plain".getBytes()), 5, null);

        assertThat(storage.objects.get("after.txt")).isEqualTo("plain".getBytes());
        assertThat(keyRows).doesNotContainKey("after.txt");
        try (InputStream in = service.download(location, "before.txt").content()) {
            assertThat(in.readAllBytes()).isEqualTo("secret".getBytes());
        }
    }

    @Test
    void anObjectStoredBeforeEncryption_isEncryptedInPlace_keepingItsKey() throws Exception {
        byte[] plaintext = "old CONFIDENTIAL contract".getBytes(StandardCharsets.UTF_8);
        storage.objects.put("docs/old.pdf", plaintext);
        String sha = java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(plaintext));

        assertThat(service.encryptExisting(location, "docs/old.pdf", plaintext.length, sha, null)).isTrue();

        // The readable original is gone; the ciphertext lives beside it; readers still use the old key.
        assertThat(storage.objects).doesNotContainKey("docs/old.pdf").containsKey("docs/old.pdf.enc");
        assertThat(new String(storage.objects.get("docs/old.pdf.enc"), StandardCharsets.ISO_8859_1))
                .doesNotContain("CONFIDENTIAL");
        try (InputStream in = service.download(location, "docs/old.pdf").content()) {
            assertThat(in.readAllBytes()).isEqualTo(plaintext);
        }
        assertThat(service.encryptExisting(location, "docs/old.pdf", plaintext.length, sha, null)).isFalse();

        service.delete(location, "docs/old.pdf");
        assertThat(storage.objects).doesNotContainKey("docs/old.pdf.enc");
        assertThat(keyRows).doesNotContainKey("docs/old.pdf");
    }

    @Test
    void encryptingInPlace_refusesBytesThatDontMatchTheUploadHash_andLeavesTheOriginal() {
        storage.objects.put("docs/changed.pdf", "tampered".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> service.encryptExisting(location, "docs/changed.pdf", 8, "00".repeat(32), null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("don't match");

        assertThat(storage.objects).containsKey("docs/changed.pdf").doesNotContainKey("docs/changed.pdf.enc");
        assertThat(keyRows).doesNotContainKey("docs/changed.pdf");
    }

    @Test
    void anUpload_isWrappedWithItsTenantsOwnKey() throws IOException {
        UUID tenant = UUID.randomUUID();
        com.oak.dms.security.auth.TenantContext.set(tenant, UUID.randomUUID());
        try {
            service.upload(location, "t.pdf", new ByteArrayInputStream("tenant file".getBytes()), 11, null);
        } finally {
            com.oak.dms.security.auth.TenantContext.clear();
        }
        StorageObjectKey row = keyRows.get("t.pdf");
        assertThat(row.getTenantId()).isEqualTo(tenant);
        assertThat(row.getKeyScope()).isEqualTo(tenant.toString());
        // Another tenant's key can't open it.
        assertThatThrownBy(() -> keys.unwrap(UUID.randomUUID().toString(), row.getWrappedKey()))
                .isInstanceOf(KeyEncryptionService.KeyServiceException.class);
        try (InputStream in = service.download(location, "t.pdf").content()) {
            assertThat(in.readAllBytes()).isEqualTo("tenant file".getBytes());
        }
    }

    /** A storage adapter in memory - only what the wrapper calls. */
    private static final class MemoryStorage implements ObjectStorageService {
        final Map<String, byte[]> objects = new HashMap<>();
        final Map<String, String> contentTypes = new HashMap<>();
        boolean failNextUpload;

        @Override
        public StorageProvider supportedProvider() {
            return StorageProvider.FILE_SYSTEM;
        }

        @Override
        public StoredObjectMetadata upload(StorageLocation location, String objectKey, InputStream content,
                                           long contentLength, String contentType) {
            if (failNextUpload) {
                failNextUpload = false;
                throw new StorageUploadException(objectKey, new IOException("disk full"));
            }
            try {
                byte[] bytes = content.readAllBytes();
                assertThat(bytes.length).as("declared length").isEqualTo(contentLength);
                objects.put(objectKey, bytes);
                contentTypes.put(objectKey, contentType);
                return new StoredObjectMetadata(objectKey, bytes.length, contentType, null, OffsetDateTime.now());
            } catch (IOException e) {
                throw new StorageUploadException(objectKey, e);
            }
        }

        @Override
        public DownloadedObject download(StorageLocation location, String objectKey) {
            byte[] bytes = objects.get(objectKey);
            return new DownloadedObject(new ByteArrayInputStream(bytes), bytes.length, contentTypes.get(objectKey));
        }

        @Override
        public void delete(StorageLocation location, String objectKey) {
            objects.remove(objectKey);
        }

        @Override
        public boolean exists(StorageLocation location, String objectKey) {
            return objects.containsKey(objectKey);
        }

        @Override
        public StoredObjectMetadata getMetadata(StorageLocation location, String objectKey) {
            return new StoredObjectMetadata(objectKey, objects.get(objectKey).length, null, null, OffsetDateTime.now());
        }

        @Override
        public Optional<String> generatePresignedDownloadUrl(StorageLocation location, String objectKey, Duration ttl) {
            return Optional.of("https://storage/" + objectKey);
        }
    }
}
