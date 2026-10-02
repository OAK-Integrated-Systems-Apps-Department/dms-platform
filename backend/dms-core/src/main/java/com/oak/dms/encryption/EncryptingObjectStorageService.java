package com.oak.dms.encryption;

import com.oak.dms.encryption.KeyEncryptionService.DataKey;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.storage.dto.DownloadedObject;
import com.oak.dms.storage.dto.StoredObjectMetadata;
import com.oak.dms.storage.entity.StorageLocation;
import com.oak.dms.storage.enums.StorageProvider;
import com.oak.dms.storage.exception.StorageUploadException;
import com.oak.dms.storage.service.ObjectStorageService;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * Encryption at rest for every stored object (Module DE, DE-FR-001/003/006),
 * wrapped around a storage adapter by
 * {@link com.oak.dms.storage.service.ObjectStorageServiceFactory#resolve}:
 * <ul>
 *   <li><b>upload</b> - a fresh data key from the key service, the object
 *       streamed through {@link ObjectCipher} so only ciphertext reaches
 *       storage, and the wrapped key recorded in storage_object_keys;</li>
 *   <li><b>download</b> - the data key unwrapped by the key service and the
 *       object decrypted as it streams to the caller (who has already been
 *       authorised - this layer never decides access);</li>
 *   <li>an object with no key row was stored before encryption (or on
 *       purpose unencrypted - see
 *       {@link com.oak.dms.storage.service.ObjectStorageServiceFactory#resolveUnencrypted})
 *       and passes through as stored.</li>
 * </ul>
 * Encrypted objects never get a presigned URL - it would hand out ciphertext.
 */
public class EncryptingObjectStorageService implements ObjectStorageService {

    private static final String STORED_CONTENT_TYPE = "application/octet-stream";

    private final ObjectStorageService delegate;
    private final KeyServices keys;
    private final ObjectKeyRecords records;
    private final ObjectCipher cipher;
    private final EncryptionProperties properties;
    private final DataKeyCache cache;

    EncryptingObjectStorageService(ObjectStorageService delegate, KeyServices keys,
                                   ObjectKeyRecords records, ObjectCipher cipher, EncryptionProperties properties,
                                   DataKeyCache cache) {
        this.delegate = delegate;
        this.keys = keys;
        this.records = records;
        this.cipher = cipher;
        this.properties = properties;
        this.cache = cache;
    }

    @Override
    public StorageProvider supportedProvider() {
        return delegate.supportedProvider();
    }

    @Override
    public StoredObjectMetadata upload(StorageLocation location, String objectKey, InputStream content,
                                       long contentLength, String contentType) {
        if (!properties.isEnabled()) {
            // Turned off: stored as is. A key row left from an earlier encrypted object at this key
            // would make the new plain bytes undecryptable, so it goes.
            StoredObjectMetadata stored = delegate.upload(location, objectKey, content, contentLength, contentType);
            records.delete(location.getStorageLocationId(), objectKey);
            return stored;
        }
        Path spooled = null;
        InputStream plaintext = content;
        long size = contentLength;
        if (size < 0) {
            // The ciphertext length must be known up front (S3 needs it); learn the plaintext's first.
            spooled = spool(content, objectKey);
            size = sizeOf(spooled, objectKey);
            plaintext = open(spooled, objectKey); // closed below, before the temp file is deleted
        }
        // The owning tenant's own master key (V57); the platform key only outside any tenant.
        UUID tenantId = TenantContext.getTenantIdOrNull();
        String scope = KeyEncryptionService.scopeOf(tenantId);
        KeyEncryptionService primary = keys.primary();
        DataKey dataKey = primary.generateDataKey(scope);
        Optional<StorageObjectKey> previous = records.find(location.getStorageLocationId(), objectKey);
        try {
            StorageObjectKey row = previous.map(this::copy).orElseGet(StorageObjectKey::new);
            row.setStorageLocationId(location.getStorageLocationId());
            row.setObjectKey(objectKey);
            row.setWrappedKey(dataKey.wrapped());
            row.setKeyProvider(primary.provider());
            row.setKeyVersion(dataKey.version());
            row.setTenantId(tenantId);
            row.setKeyScope(scope);
            row.setAlgorithm(ObjectCipher.ALGORITHM);
            row.setPlaintextSize(size);
            row.setPhysicalObjectKey(null); // a fresh upload lives at its own key
            row.setRewrappedAt(null);
            if (row.getCreatedAt() == null) {
                row.setCreatedAt(OffsetDateTime.now());
            }
            // The key first: if the upload then fails, the row is undone below; if this process dies
            // in between, the row points at a missing object - which is unreadable anyway.
            records.save(row);
            byte[] aad = ObjectCipher.associatedData(location.getStorageLocationId().toString(), objectKey);
            StoredObjectMetadata stored;
            try {
                stored = delegate.upload(location, objectKey, cipher.encrypting(plaintext, dataKey.plaintext(), aad),
                        cipher.ciphertextSize(dataKey.plaintext(), size), STORED_CONTENT_TYPE);
            } catch (RuntimeException e) {
                undo(location, objectKey, previous);
                throw e;
            }
            // Replacing an object that had been encrypted in place: its old separate copy is no longer used.
            previous.map(StorageObjectKey::getPhysicalObjectKey).ifPresent(old -> deleteObjectQuietly(location, old));
            return new StoredObjectMetadata(objectKey, size, contentType, stored.contentHash(), stored.lastModified());
        } finally {
            Arrays.fill(dataKey.plaintext(), (byte) 0);
            if (spooled != null) {
                closeQuietly(plaintext);
                deleteQuietly(spooled);
            }
        }
    }

    @Override
    public DownloadedObject download(StorageLocation location, String objectKey) {
        Optional<StorageObjectKey> row = records.find(location.getStorageLocationId(), objectKey);
        if (row.isEmpty()) {
            return delegate.download(location, objectKey); // not encrypted (stored before, or deliberately plain)
        }
        DownloadedObject stored = delegate.download(location, physicalKey(row.get()));
        // Unwrapped by whichever service wrapped it, with the master key of its own scope - or taken
        // from the short-lived cache when the same key was just used (a bulk export, repeated previews).
        StorageObjectKey key = row.get();
        byte[] dataKey = cache.get(key.getKeyProvider(), key.getKeyScope(), key.getWrappedKey()).orElse(null);
        if (dataKey == null) {
            dataKey = keys.forProvider(key.getKeyProvider()).unwrap(key.getKeyScope(), key.getWrappedKey());
            cache.put(key.getKeyProvider(), key.getKeyScope(), key.getWrappedKey(), dataKey);
        }
        try {
            byte[] aad = ObjectCipher.associatedData(location.getStorageLocationId().toString(), objectKey);
            InputStream plaintext = cipher.decrypting(stored.content(), dataKey, aad);
            return new DownloadedObject(plaintext, row.get().getPlaintextSize(), stored.contentType());
        } catch (RuntimeException e) {
            closeQuietly(stored.content());
            throw e;
        } finally {
            Arrays.fill(dataKey, (byte) 0);
        }
    }

    @Override
    public void delete(StorageLocation location, String objectKey) {
        Optional<StorageObjectKey> row = records.find(location.getStorageLocationId(), objectKey);
        delegate.delete(location, objectKey);
        row.map(StorageObjectKey::getPhysicalObjectKey).ifPresent(physical -> delegate.delete(location, physical));
        records.delete(location.getStorageLocationId(), objectKey);
    }

    @Override
    public boolean exists(StorageLocation location, String objectKey) {
        return delegate.exists(location, records.find(location.getStorageLocationId(), objectKey)
                .map(this::physicalKey).orElse(objectKey));
    }

    @Override
    public StoredObjectMetadata getMetadata(StorageLocation location, String objectKey) {
        Optional<StorageObjectKey> row = records.find(location.getStorageLocationId(), objectKey);
        if (row.isEmpty()) {
            return delegate.getMetadata(location, objectKey);
        }
        StoredObjectMetadata stored = delegate.getMetadata(location, physicalKey(row.get()));
        return new StoredObjectMetadata(objectKey, row.get().getPlaintextSize(), stored.contentType(),
                stored.contentHash(), stored.lastModified());
    }

    /**
     * Encrypts an object that was stored before encryption at rest, leaving
     * its key - the one its document version points at - unchanged (version
     * keys are immutable). The ciphertext goes to a separate object; the
     * plaintext is re-hashed on the way and must match {@code expectedSha256}
     * (the hash recorded at upload); then saving the key row switches readers
     * over in one step, and only after that is the readable original deleted.
     *
     * @return false when the object was already encrypted (nothing done)
     */
    public boolean encryptExisting(StorageLocation location, String objectKey, long sizeBytes,
                                   String expectedSha256, UUID tenantId) {
        if (records.find(location.getStorageLocationId(), objectKey).isPresent()) {
            return false;
        }
        String physical = objectKey + ".enc";
        String scope = KeyEncryptionService.scopeOf(tenantId);
        KeyEncryptionService primary = keys.primary();
        DataKey dataKey = primary.generateDataKey(scope);
        try {
            byte[] aad = ObjectCipher.associatedData(location.getStorageLocationId().toString(), objectKey);
            MessageDigest digest = sha256();
            DownloadedObject source = delegate.download(location, objectKey);
            try (InputStream hashing = new DigestInputStream(source.content(), digest)) {
                delegate.upload(location, physical, cipher.encrypting(hashing, dataKey.plaintext(), aad),
                        cipher.ciphertextSize(dataKey.plaintext(), sizeBytes), STORED_CONTENT_TYPE);
            } catch (IOException e) {
                deleteObjectQuietly(location, physical);
                throw new StorageUploadException(physical, e);
            } catch (RuntimeException e) {
                deleteObjectQuietly(location, physical);
                throw e;
            }
            String actual = HexFormat.of().formatHex(digest.digest());
            if (expectedSha256 != null && !actual.equalsIgnoreCase(expectedSha256)) {
                deleteObjectQuietly(location, physical);
                throw new IllegalStateException("the stored bytes don't match the hash recorded at upload - left as is");
            }
            try {
                records.save(StorageObjectKey.builder()
                        .storageLocationId(location.getStorageLocationId())
                        .objectKey(objectKey)
                        .physicalObjectKey(physical)
                        .wrappedKey(dataKey.wrapped())
                        .keyProvider(primary.provider())
                        .keyVersion(dataKey.version())
                        .tenantId(tenantId)
                        .keyScope(scope)
                        .algorithm(ObjectCipher.ALGORITHM)
                        .plaintextSize(sizeBytes)
                        .createdAt(OffsetDateTime.now())
                        .build());
            } catch (RuntimeException e) {
                deleteObjectQuietly(location, physical); // not switched over - the original stays in use
                throw e;
            }
            // Switched over: the readable original can go. If this fails it lingers, unused, until removed.
            deleteObjectQuietly(location, objectKey);
            return true;
        } finally {
            Arrays.fill(dataKey.plaintext(), (byte) 0);
        }
    }

    private String physicalKey(StorageObjectKey row) {
        return row.getPhysicalObjectKey() != null ? row.getPhysicalObjectKey() : row.getObjectKey();
    }

    private void deleteObjectQuietly(StorageLocation location, String objectKey) {
        try {
            delegate.delete(location, objectKey);
        } catch (RuntimeException ignored) {
            // a leftover object is unused; nothing reads it
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available on this JVM", e);
        }
    }

    @Override
    public boolean encryptsAtRest(StorageLocation location) {
        return properties.isEnabled() || delegate.encryptsAtRest(location);
    }

    @Override
    public Optional<String> generatePresignedDownloadUrl(StorageLocation location, String objectKey, Duration ttl) {
        if (records.find(location.getStorageLocationId(), objectKey).isPresent()) {
            return Optional.empty(); // the URL would serve ciphertext - downloads go through the application
        }
        return delegate.generatePresignedDownloadUrl(location, objectKey, ttl);
    }

    /** Whether an object is encrypted - for the "encrypt existing documents" job. */
    public boolean isEncrypted(StorageLocation location, String objectKey) {
        return records.find(location.getStorageLocationId(), objectKey).isPresent();
    }

    private void undo(StorageLocation location, String objectKey, Optional<StorageObjectKey> previous) {
        if (previous.isPresent()) {
            records.save(previous.get());
        } else {
            records.delete(location.getStorageLocationId(), objectKey);
        }
    }

    /** A detached copy, so the previous row can be put back exactly if the upload fails. */
    private StorageObjectKey copy(StorageObjectKey row) {
        return StorageObjectKey.builder()
                .storageObjectKeyId(row.getStorageObjectKeyId())
                .storageLocationId(row.getStorageLocationId())
                .objectKey(row.getObjectKey())
                .wrappedKey(row.getWrappedKey())
                .keyProvider(row.getKeyProvider())
                .keyVersion(row.getKeyVersion())
                .tenantId(row.getTenantId())
                .keyScope(row.getKeyScope())
                .algorithm(row.getAlgorithm())
                .plaintextSize(row.getPlaintextSize())
                .physicalObjectKey(row.getPhysicalObjectKey())
                .createdAt(row.getCreatedAt())
                .rewrappedAt(row.getRewrappedAt())
                .build();
    }

    private static Path spool(InputStream content, String objectKey) {
        try {
            Path file = Files.createTempFile("dms-upload-", ".part");
            Files.copy(content, file, StandardCopyOption.REPLACE_EXISTING);
            return file;
        } catch (IOException e) {
            throw new StorageUploadException(objectKey, e);
        }
    }

    private static long sizeOf(Path file, String objectKey) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            throw new StorageUploadException(objectKey, e);
        }
    }

    private static InputStream open(Path file, String objectKey) {
        try {
            return Files.newInputStream(file);
        } catch (IOException e) {
            throw new StorageUploadException(objectKey, e);
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // a leftover temp file holds plaintext only on the application's own disk; the OS cleans temp
        }
    }

    private static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException ignored) {
            // nothing more to do - the stream is being abandoned
        }
    }
}
