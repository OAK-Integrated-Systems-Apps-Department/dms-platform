package com.oak.dms.storage.adapter;

import com.oak.dms.storage.dto.DownloadedObject;
import com.oak.dms.storage.dto.StoredObjectMetadata;
import com.oak.dms.storage.entity.StorageLocation;
import com.oak.dms.storage.enums.StorageProvider;
import com.oak.dms.storage.exception.StorageConfigurationException;
import com.oak.dms.storage.exception.StorageObjectNotFoundException;
import com.oak.dms.storage.helper.StorageProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileSystemStorageAdapterTest {

    @TempDir
    Path tempDir;

    private final FileSystemStorageAdapter adapter = new FileSystemStorageAdapter(new StorageProperties());

    @Test
    void uploadDownloadDeleteExistsAndMetadata_roundTripCorrectly() {
        StorageLocation location = locationAt(tempDir);
        byte[] content = "hello filesystem storage".getBytes(StandardCharsets.UTF_8);
        String key = "documents/tenant/doc/v1/version-a";

        StoredObjectMetadata uploaded = adapter.upload(location, key, new ByteArrayInputStream(content),
                content.length, "text/plain");
        assertThat(uploaded.sizeBytes()).isEqualTo(content.length);

        assertThat(adapter.exists(location, key)).isTrue();

        DownloadedObject downloaded = adapter.download(location, key);
        byte[] read;
        try (var in = downloaded.content()) {
            read = in.readAllBytes();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        assertThat(read).isEqualTo(content);
        assertThat(downloaded.sizeBytes()).isEqualTo(content.length);

        StoredObjectMetadata metadata = adapter.getMetadata(location, key);
        assertThat(metadata.sizeBytes()).isEqualTo(content.length);

        adapter.delete(location, key);
        assertThat(adapter.exists(location, key)).isFalse();
    }

    @Test
    void download_missingObject_throwsNotFound() {
        StorageLocation location = locationAt(tempDir);
        assertThatThrownBy(() -> adapter.download(location, "documents/does/not/exist"))
                .isInstanceOf(StorageObjectNotFoundException.class);
    }

    @Test
    void getMetadata_missingObject_throwsNotFound() {
        StorageLocation location = locationAt(tempDir);
        assertThatThrownBy(() -> adapter.getMetadata(location, "documents/does/not/exist"))
                .isInstanceOf(StorageObjectNotFoundException.class);
    }

    @Test
    void delete_missingObject_isANoOp() {
        StorageLocation location = locationAt(tempDir);
        adapter.delete(location, "documents/never/existed");
    }

    @Test
    void objectKeyAttemptingToEscapeTheBasePath_isRejected() {
        StorageLocation location = locationAt(tempDir);
        byte[] content = "irrelevant".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> adapter.upload(location, "../../etc/passwd", new ByteArrayInputStream(content),
                content.length, "text/plain"))
                .isInstanceOf(StorageConfigurationException.class);
    }

    @Test
    void generatePresignedDownloadUrl_isAlwaysEmpty() {
        StorageLocation location = locationAt(tempDir);
        assertThat(adapter.generatePresignedDownloadUrl(location, "any/key", java.time.Duration.ofMinutes(5)))
                .isEmpty();
    }

    private static StorageLocation locationAt(Path basePath) {
        return StorageLocation.builder()
                .storageLocationId(UUID.randomUUID())
                .provider(StorageProvider.FILE_SYSTEM)
                .basePath(basePath.toString())
                .status(StorageLocation.Status.active)
                .build();
    }
}
