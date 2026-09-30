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
    /** Platform-default staging location - where uploads wait while they are scanned (see com.oak.dms.scanning). */
    private final PurposeTarget staging = new PurposeTarget("/data/dms/staging");
    /** Platform-default quarantine location - where infected uploads are isolated (see com.oak.dms.scanning). */
    private final PurposeTarget quarantine = new PurposeTarget("/data/dms/quarantine");

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

    /**
     * Seeds the platform-default staging/quarantine {@link com.oak.dms.storage.entity.StorageLocation}.
     * Blank {@code bucket}/{@code endpoint}/{@code region} fall back to the same
     * deployment-wide {@code s3.*}/{@code azure-blob.*} values the document
     * location uses - which is why {@link StorageLocationBootstrap} refuses to
     * start if the resulting physical target overlaps the document location's.
     */
    @Getter
    @Setter
    public static class PurposeTarget {
        private StorageProvider provider = StorageProvider.FILE_SYSTEM;
        /** FILE_SYSTEM only. */
        private String basePath;
        /** S3 bucket, or AZURE_BLOB container. */
        private String bucket;
        private String endpoint;
        /** S3 only. */
        private String region;
        /** S3 only. */
        private boolean pathStyleAccess = false;
        /** Optional - see {@code StorageLocation.credentialRef}. */
        private String credentialRef;

        public PurposeTarget() {
        }

        PurposeTarget(String defaultBasePath) {
            this.basePath = defaultBasePath;
        }
    }
}
