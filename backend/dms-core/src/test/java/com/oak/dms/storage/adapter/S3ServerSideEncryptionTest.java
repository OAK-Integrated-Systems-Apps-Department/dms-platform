package com.oak.dms.storage.adapter;

import com.oak.dms.storage.entity.StorageLocation;
import com.oak.dms.storage.enums.StoragePurpose;
import com.oak.dms.storage.enums.StorageProvider;
import com.oak.dms.storage.exception.StorageConfigurationException;
import com.oak.dms.storage.helper.StorageProperties;
import com.oak.dms.storage.service.ObjectStorageService;
import com.oak.dms.storage.service.ObjectStorageServiceFactory;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Server-side encryption on S3: requested per location or per purpose, and
 * required for a staging area a cloud malware scanner reads in place.
 */
class S3ServerSideEncryptionTest {

    private final StorageProperties properties = new StorageProperties();
    private final S3ObjectStorageAdapter adapter = new S3ObjectStorageAdapter(properties, new MockEnvironment());
    private final S3Client s3Client = mock(S3Client.class);

    @Test
    void aStagingAreaWithSseKms_sendsTheEncryptionHeaders_andCountsAsEncryptedAtRest() {
        properties.getStaging().setServerSideEncryption("aws:kms");
        properties.getStaging().setKmsKeyId("arn:aws:kms:eu-west-1:1:key/abc");
        StorageLocation staging = location(StoragePurpose.staging, null);

        upload(staging);

        PutObjectRequest sent = captured();
        assertThat(sent.serverSideEncryption()).isEqualTo(ServerSideEncryption.AWS_KMS);
        assertThat(sent.ssekmsKeyId()).isEqualTo("arn:aws:kms:eu-west-1:1:key/abc");
        assertThat(adapter.encryptsAtRest(staging)).isTrue();
    }

    @Test
    void aLocationsOwnConfiguration_winsOverTheDeploymentDefault() {
        properties.getS3().setServerSideEncryption("aws:kms");
        StorageLocation location = location(StoragePurpose.document, "{\"serverSideEncryption\":\"AES256\"}");

        upload(location);

        assertThat(captured().serverSideEncryption()).isEqualTo(ServerSideEncryption.AES256);
        assertThat(captured().ssekmsKeyId()).isNull();
    }

    @Test
    void withoutServerSideEncryption_nothingIsRequested_andAnUnknownSettingIsRefused() {
        StorageLocation plain = location(StoragePurpose.document, null);
        upload(plain);
        assertThat(captured().serverSideEncryption()).isNull();
        assertThat(adapter.encryptsAtRest(plain)).isFalse();

        StorageLocation wrong = location(StoragePurpose.document, "{\"serverSideEncryption\":\"rot13\"}");
        assertThatThrownBy(() -> adapter.encryptsAtRest(wrong))
                .isInstanceOf(StorageConfigurationException.class).hasMessageContaining("AES256 or aws:kms");
    }

    @Test
    void aCloudScannersStagingArea_mustBeEncryptedByTheStorage() {
        ObjectStorageServiceFactory factory = new ObjectStorageServiceFactory(List.of(adapter));
        StorageLocation unencrypted = location(StoragePurpose.staging, null);
        assertThatThrownBy(() -> factory.resolveForInPlaceScan(unencrypted))
                .isInstanceOf(StorageConfigurationException.class).hasMessageContaining("server-side encryption");

        properties.getStaging().setServerSideEncryption("AES256");
        ObjectStorageService allowed = factory.resolveForInPlaceScan(unencrypted);
        assertThat(allowed).isSameAs(adapter);
    }

    private void upload(StorageLocation location) {
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().eTag("\"e\"").build());
        adapter.upload(location, "k", new ByteArrayInputStream(new byte[3]), 3, "application/pdf");
    }

    private PutObjectRequest captured() {
        ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client, org.mockito.Mockito.atLeastOnce()).putObject(request.capture(), any(RequestBody.class));
        return request.getValue();
    }

    private StorageLocation location(StoragePurpose purpose, String configuration) {
        UUID id = UUID.randomUUID();
        adapter.clients.put(id, s3Client);
        return StorageLocation.builder().storageLocationId(id).provider(StorageProvider.S3).purpose(purpose)
                .bucket("b").region("eu-west-1").configuration(configuration).build();
    }
}
