package com.oak.dms.storage.adapter;

import com.azure.core.http.HttpResponse;
import com.azure.core.http.rest.Response;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.models.BlobProperties;
import com.azure.storage.blob.models.BlobStorageException;
import com.azure.storage.blob.models.BlockBlobItem;
import com.azure.storage.blob.options.BlobParallelUploadOptions;
import com.azure.storage.blob.specialized.BlobInputStream;
import com.oak.dms.storage.dto.DownloadedObject;
import com.oak.dms.storage.dto.StoredObjectMetadata;
import com.oak.dms.storage.entity.StorageLocation;
import com.oak.dms.storage.enums.StorageProvider;
import com.oak.dms.storage.exception.StorageConfigurationException;
import com.oak.dms.storage.exception.StorageDownloadException;
import com.oak.dms.storage.exception.StorageObjectNotFoundException;
import com.oak.dms.storage.exception.StorageUploadException;
import com.oak.dms.storage.helper.StorageProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Seeds a mocked {@link BlobContainerClient}/{@link BlobClient} directly into
 * the adapter's package-private client cache (see that field's own doc
 * comment) rather than letting a real one be built, so these tests never
 * touch the network - same approach as {@code S3ObjectStorageAdapterTest}.
 * The actual endpoint/credential builder wiring is exercised separately
 * (builds a real client, never calls it); live-server behavior against a
 * real Azure Storage account is a manual check only, per
 * docs/storage-architecture.md's testing-boundary note.
 */
class AzureBlobStorageAdapterTest {

    private final AzureBlobStorageAdapter adapter =
            new AzureBlobStorageAdapter(new StorageProperties(), new MockEnvironment());
    private final BlobContainerClient containerClient = mock(BlobContainerClient.class);
    private final BlobClient blobClient = mock(BlobClient.class);

    @Test
    void upload_delegatesToUploadWithResponseAndReturnsMetadata() {
        StorageLocation location = seedLocation();
        when(containerClient.getBlobClient("key/a")).thenReturn(blobClient);
        BlockBlobItem item = new BlockBlobItem("\"abc123\"", Instant.parse("2025-01-01T00:00:00Z").atOffset(ZoneOffset.UTC),
                null, Boolean.FALSE, null, null, null);
        @SuppressWarnings("unchecked")
        Response<BlockBlobItem> response = mock(Response.class);
        when(response.getValue()).thenReturn(item);
        when(blobClient.uploadWithResponse(any(BlobParallelUploadOptions.class), any(), any())).thenReturn(response);

        byte[] content = "hello azure".getBytes(StandardCharsets.UTF_8);
        StoredObjectMetadata result = adapter.upload(location, "key/a", new ByteArrayInputStream(content),
                content.length, "text/plain");

        assertThat(result.objectKey()).isEqualTo("key/a");
        assertThat(result.sizeBytes()).isEqualTo(content.length);
        assertThat(result.contentHash()).isEqualTo("abc123");
    }

    @Test
    void upload_sdkFailure_isTranslatedToStorageUploadException() {
        StorageLocation location = seedLocation();
        when(containerClient.getBlobClient("key/a")).thenReturn(blobClient);
        BlobStorageException failure = notFound("boom");
        when(blobClient.uploadWithResponse(any(BlobParallelUploadOptions.class), any(), any()))
                .thenThrow(failure);

        byte[] content = "x".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> adapter.upload(location, "key/a", new ByteArrayInputStream(content),
                content.length, "text/plain"))
                .isInstanceOf(StorageUploadException.class);
    }

    @Test
    void download_delegatesToOpenInputStream() {
        StorageLocation location = seedLocation();
        when(containerClient.getBlobClient("key/a")).thenReturn(blobClient);
        BlobInputStream stream = mock(BlobInputStream.class);
        BlobProperties properties = mock(BlobProperties.class);
        when(properties.getBlobSize()).thenReturn(42L);
        when(properties.getContentType()).thenReturn("text/plain");
        when(stream.getProperties()).thenReturn(properties);
        when(blobClient.openInputStream()).thenReturn(stream);

        DownloadedObject downloaded = adapter.download(location, "key/a");

        assertThat(downloaded.sizeBytes()).isEqualTo(42L);
        assertThat(downloaded.contentType()).isEqualTo("text/plain");
    }

    @Test
    void download_missingKey_throwsStorageObjectNotFoundException() {
        StorageLocation location = seedLocation();
        when(containerClient.getBlobClient("key/missing")).thenReturn(blobClient);
        BlobStorageException failure = notFound("no such blob");
        when(blobClient.openInputStream()).thenThrow(failure);

        assertThatThrownBy(() -> adapter.download(location, "key/missing"))
                .isInstanceOf(StorageObjectNotFoundException.class);
    }

    @Test
    void delete_delegatesToDeleteIfExists() {
        StorageLocation location = seedLocation();
        when(containerClient.getBlobClient("key/a")).thenReturn(blobClient);
        when(blobClient.deleteIfExists()).thenReturn(true);

        adapter.delete(location, "key/a");

        verify(blobClient).deleteIfExists();
    }

    @Test
    void exists_missingKey_returnsFalseRatherThanThrowing() {
        StorageLocation location = seedLocation();
        when(containerClient.getBlobClient("key/missing")).thenReturn(blobClient);
        when(blobClient.exists()).thenReturn(false);

        assertThat(adapter.exists(location, "key/missing")).isFalse();
    }

    @Test
    void exists_connectionFailure_isTranslatedRatherThanLeakingAnSdkException() {
        StorageLocation location = seedLocation();
        when(containerClient.getBlobClient("key/a")).thenReturn(blobClient);
        when(blobClient.exists()).thenThrow(new RuntimeException("unreachable"));

        assertThatThrownBy(() -> adapter.exists(location, "key/a"))
                .isInstanceOf(StorageDownloadException.class);
    }

    @Test
    void getMetadata_delegatesToGetProperties() {
        StorageLocation location = seedLocation();
        when(containerClient.getBlobClient("key/a")).thenReturn(blobClient);
        BlobProperties properties = mock(BlobProperties.class);
        when(properties.getBlobSize()).thenReturn(42L);
        when(properties.getContentType()).thenReturn("application/pdf");
        when(properties.getETag()).thenReturn("\"deadbeef\"");
        when(properties.getLastModified()).thenReturn(Instant.parse("2025-01-01T00:00:00Z").atOffset(ZoneOffset.UTC));
        when(blobClient.getProperties()).thenReturn(properties);

        StoredObjectMetadata metadata = adapter.getMetadata(location, "key/a");

        assertThat(metadata.sizeBytes()).isEqualTo(42L);
        assertThat(metadata.contentType()).isEqualTo("application/pdf");
        assertThat(metadata.contentHash()).isEqualTo("deadbeef");
    }

    @Test
    void containerClient_withCustomEndpointAndCredentials_buildsSuccessfully() {
        // Exercises the REAL containerClient() builder path (not seeded) -
        // proves endpoint()/credential() wiring from a StorageLocation
        // doesn't throw at construction time. Never makes a network call -
        // building a client doesn't connect anywhere. Live-server behavior is
        // confirmed manually against a real Azure Storage account, per
        // docs/storage-architecture.md.
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("STORAGE_CRED_AZURE_TEST_ACCOUNT_NAME", "testaccount");
        environment.setProperty("STORAGE_CRED_AZURE_TEST_ACCOUNT_KEY", "dGVzdC1hY2NvdW50LWtleQ==");
        AzureBlobStorageAdapter azureAdapter = new AzureBlobStorageAdapter(new StorageProperties(), environment);

        StorageLocation azureLocation = StorageLocation.builder()
                .storageLocationId(UUID.randomUUID())
                .provider(StorageProvider.AZURE_BLOB)
                .endpoint("https://testaccount.blob.core.windows.net")
                .bucket("test-container")
                .credentialRef("azure-test")
                .build();

        assertThat(azureAdapter.containerClient(azureLocation)).isNotNull();
    }

    @Test
    void upload_missingEndpoint_throwsStorageConfigurationExceptionRatherThanWrapping() {
        // Location has no endpoint and no deployment-wide dms.storage.azure-blob.endpoint
        // default configured either - containerClient() must fail before any
        // upload is attempted, and that StorageConfigurationException must
        // reach the caller as-is (not rewrapped into StorageUploadException),
        // since it's a deployment misconfiguration (500), not an upload
        // failure (502). Uses a fresh, unseeded adapter so the real
        // containerClient() builder path runs.
        AzureBlobStorageAdapter azureAdapter = new AzureBlobStorageAdapter(new StorageProperties(), new MockEnvironment());
        StorageLocation location = StorageLocation.builder()
                .storageLocationId(UUID.randomUUID())
                .provider(StorageProvider.AZURE_BLOB)
                .bucket("test-container")
                .build();

        byte[] content = "x".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> azureAdapter.upload(location, "key/a", new ByteArrayInputStream(content),
                content.length, "text/plain"))
                .isInstanceOf(StorageConfigurationException.class);
    }

    private static BlobStorageException notFound(String message) {
        HttpResponse response = mock(HttpResponse.class);
        when(response.getStatusCode()).thenReturn(404);
        return new BlobStorageException(message, response, null);
    }

    private StorageLocation seedLocation() {
        UUID locationId = UUID.randomUUID();
        adapter.containerClients.put(locationId, containerClient);
        return StorageLocation.builder()
                .storageLocationId(locationId)
                .provider(StorageProvider.AZURE_BLOB)
                .bucket("test-container")
                .build();
    }
}
