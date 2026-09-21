package com.oak.dms.storage.adapter;

import com.oak.dms.storage.dto.DownloadedObject;
import com.oak.dms.storage.dto.StoredObjectMetadata;
import com.oak.dms.storage.entity.StorageLocation;
import com.oak.dms.storage.enums.StorageProvider;
import com.oak.dms.storage.exception.StorageDownloadException;
import com.oak.dms.storage.exception.StorageObjectNotFoundException;
import com.oak.dms.storage.exception.StorageUploadException;
import com.oak.dms.storage.helper.StorageProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Seeds a mocked {@link S3Client} directly into the adapter's package-private
 * client cache (see that field's own doc comment) rather than letting a real
 * one be built, so these tests never touch the network - the actual
 * endpoint-override/path-style builder wiring is exercised separately
 * (builds a real client, never calls it) and confirmed against a live
 * MinIO manually, per docs/storage-architecture.md's testing-boundary note.
 */
class S3ObjectStorageAdapterTest {

    private final S3ObjectStorageAdapter adapter =
            new S3ObjectStorageAdapter(new StorageProperties(), new MockEnvironment());
    private final S3Client s3Client = mock(S3Client.class);

    @Test
    void upload_delegatesToPutObjectAndReturnsMetadata() {
        StorageLocation location = seedLocation();
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().eTag("\"abc123\"").build());

        byte[] content = "hello s3".getBytes(StandardCharsets.UTF_8);
        StoredObjectMetadata result = adapter.upload(location, "key/a", new ByteArrayInputStream(content),
                content.length, "text/plain");

        assertThat(result.objectKey()).isEqualTo("key/a");
        assertThat(result.sizeBytes()).isEqualTo(content.length);
        assertThat(result.contentHash()).isEqualTo("abc123");
    }

    @Test
    void upload_sdkFailure_isTranslatedToStorageUploadException() {
        StorageLocation location = seedLocation();
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(SdkClientException.create("boom"));

        byte[] content = "x".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> adapter.upload(location, "key/a", new ByteArrayInputStream(content),
                content.length, "text/plain"))
                .isInstanceOf(StorageUploadException.class);
    }

    @Test
    void download_delegatesToGetObject() {
        StorageLocation location = seedLocation();
        byte[] content = "hello download".getBytes(StandardCharsets.UTF_8);
        GetObjectResponse response = GetObjectResponse.builder().contentLength((long) content.length)
                .contentType("text/plain").build();
        when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(
                new ResponseInputStream<>(response, AbortableInputStream.create(new ByteArrayInputStream(content))));

        DownloadedObject downloaded = adapter.download(location, "key/a");

        assertThat(downloaded.sizeBytes()).isEqualTo(content.length);
        assertThat(downloaded.contentType()).isEqualTo("text/plain");
    }

    @Test
    void download_missingKey_throwsStorageObjectNotFoundException() {
        StorageLocation location = seedLocation();
        when(s3Client.getObject(any(GetObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().message("no such key").build());

        assertThatThrownBy(() -> adapter.download(location, "key/missing"))
                .isInstanceOf(StorageObjectNotFoundException.class);
    }

    @Test
    void delete_delegatesToDeleteObject() {
        StorageLocation location = seedLocation();
        when(s3Client.deleteObject(any(DeleteObjectRequest.class)))
                .thenReturn(DeleteObjectResponse.builder().build());

        adapter.delete(location, "key/a");

        verify(s3Client).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    void exists_missingKey_returnsFalseRatherThanThrowing() {
        StorageLocation location = seedLocation();
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().message("no such key").build());

        assertThat(adapter.exists(location, "key/missing")).isFalse();
    }

    @Test
    void exists_connectionFailure_isTranslatedRatherThanLeakingAnSdkException() {
        StorageLocation location = seedLocation();
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenThrow(SdkClientException.create("unreachable"));

        assertThatThrownBy(() -> adapter.exists(location, "key/a"))
                .isInstanceOf(StorageDownloadException.class);
    }

    @Test
    void getMetadata_delegatesToHeadObject() {
        StorageLocation location = seedLocation();
        when(s3Client.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder()
                .contentLength(42L).contentType("application/pdf").eTag("\"deadbeef\"")
                .lastModified(Instant.parse("2025-01-01T00:00:00Z")).build());

        StoredObjectMetadata metadata = adapter.getMetadata(location, "key/a");

        assertThat(metadata.sizeBytes()).isEqualTo(42L);
        assertThat(metadata.contentType()).isEqualTo("application/pdf");
        assertThat(metadata.contentHash()).isEqualTo("deadbeef");
    }

    @Test
    void client_withCustomEndpointAndPathStyleAccess_buildsSuccessfully() {
        // Exercises the REAL client() builder path (not seeded) - proves
        // endpointOverride()/forcePathStyle() wiring from a StorageLocation
        // doesn't throw at construction time, which is what makes this one
        // adapter able to target MinIO/SeaweedFS instead of real AWS S3.
        // Never makes a network call - building a client doesn't connect
        // anywhere. Live-server behavior is confirmed manually against a
        // real MinIO, per docs/storage-architecture.md.
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("STORAGE_CRED_MINIO_TEST_ACCESS_KEY", "test-access-key");
        environment.setProperty("STORAGE_CRED_MINIO_TEST_SECRET_KEY", "test-secret-key");
        S3ObjectStorageAdapter minioAdapter = new S3ObjectStorageAdapter(new StorageProperties(), environment);

        StorageLocation minioLocation = StorageLocation.builder()
                .storageLocationId(UUID.randomUUID())
                .provider(StorageProvider.S3)
                .endpoint("http://localhost:9000")
                .region("us-east-1")
                .bucket("test-bucket")
                .pathStyleAccess(true)
                .credentialRef("minio-test")
                .build();

        assertThat(minioAdapter.client(minioLocation)).isNotNull();
    }

    private StorageLocation seedLocation() {
        UUID locationId = UUID.randomUUID();
        adapter.clients.put(locationId, s3Client);
        return StorageLocation.builder()
                .storageLocationId(locationId)
                .provider(StorageProvider.S3)
                .bucket("test-bucket")
                .region("us-east-1")
                .build();
    }
}
