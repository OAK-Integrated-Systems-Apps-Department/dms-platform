package com.oak.dms.storage.helper;

import com.oak.dms.storage.enums.StorageProvider;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Binds the {@code dms.storage.*} configuration keys - the deployment-wide
 * defaults {@link StorageLocationBootstrap} seeds the platform-default
 * {@link com.oak.dms.storage.entity.StorageLocation} from, and that
 * {@link com.oak.dms.storage.adapter.S3ObjectStorageAdapter}/
 * {@link com.oak.dms.storage.adapter.AzureBlobStorageAdapter}
 * fall back to for any location whose {@code credentialRef} is blank.
 * Never logged in full (holds {@code s3.accessKey}/{@code s3.secretKey}/
 * {@code azureBlob.accountKey}).
 */
@Component
@ConfigurationProperties(prefix = "dms.storage")
@Getter
@Setter
public class StorageProperties {

    private StorageProvider defaultProvider = StorageProvider.FILE_SYSTEM;
    private final S3 s3 = new S3();
    private final FileSystem filesystem = new FileSystem();
    private final AzureBlob azureBlob = new AzureBlob();

    @Getter
    @Setter
    public static class S3 {
        private String endpoint;
        private String region = "us-east-1";
        private String bucket;
        private String accessKey;
        private String secretKey;
        private boolean pathStyleAccess = false;
    }

    @Getter
    @Setter
    public static class FileSystem {
        private String basePath = "/data/dms/documents";
    }

    /** No AWS-style "blank endpoint = provider default" here - Azure has no single universal endpoint. */
    @Getter
    @Setter
    public static class AzureBlob {
        private String endpoint;
        private String container;
        private String accountName;
        private String accountKey;
    }
}
