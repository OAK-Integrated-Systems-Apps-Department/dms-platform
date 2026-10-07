package com.oak.dms.storage.adapter;

import com.oak.dms.storage.dto.DownloadedObject;
import com.oak.dms.storage.dto.StoredObjectMetadata;
import com.oak.dms.storage.entity.StorageLocation;
import com.oak.dms.storage.enums.StorageProvider;
import com.oak.dms.storage.exception.StorageConfigurationException;
import com.oak.dms.storage.exception.StorageDeleteException;
import com.oak.dms.storage.exception.StorageDownloadException;
import com.oak.dms.storage.exception.StorageObjectNotFoundException;
import com.oak.dms.storage.exception.StorageUploadException;
import com.oak.dms.storage.helper.StorageProperties;
import com.oak.dms.storage.service.ObjectStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.net.URI;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The single adapter that talks to AWS S3, MinIO, and the SeaweedFS S3
 * gateway - all three are S3-compatible, and AWS SDK v2's
 * {@code endpointOverride}/{@code forcePathStyle} builder options are all
 * that's needed to point the same client at any of them. Never create a
 * separate {@code MinIOObjectStorageAdapter}/{@code SeaweedFSObjectStorageAdapter}
 * unless a real provider-specific feature (not just a different endpoint)
 * actually needs one.
 * <p>
 * Builds and caches one {@link S3Client}/{@link S3Presigner} pair per
 * {@link StorageLocation} (different locations can have different
 * endpoint/region/credentials), rather than a single deployment-wide client.
 * Never logs a resolved access/secret key or a generated presigned URL.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class S3ObjectStorageAdapter implements ObjectStorageService {

    private final StorageProperties storageProperties;
    private final Environment environment;

    // Package-private (not private) so S3ObjectStorageAdapterTest can seed a
    // mock S3Client/S3Presigner directly for a given location, bypassing the
    // real builder in client()/presigner() below entirely - same-package
    // test seam only, not a public API.
    final ConcurrentHashMap<UUID, S3Client> clients = new ConcurrentHashMap<>();
    final ConcurrentHashMap<UUID, S3Presigner> presigners = new ConcurrentHashMap<>();

    @Override
    public StorageProvider supportedProvider() {
        return StorageProvider.S3;
    }

    @Override
    /*
     * FOLLOW-UP (found 2026-10-07, while lifting the 2047 MB upload limit): one PutObject
     * takes at most 5 GB, so on an S3 staging or document location an upload over 5 GB
     * fails here with a StorageUploadException ("Staging upload failed"). FILE_SYSTEM and
     * Azure Blob (which splits into blocks itself) have no such cap. Not fixed in that
     * change, which was about the scanner's limit. The fix: above a threshold (say
     * 100 MB), upload in parts - the SDK's S3TransferManager / multipart upload
     * (CreateMultipartUpload, UploadPart, CompleteMultipartUpload; abort on failure) -
     * keeping the server-side encryption settings on the CreateMultipartUpload request.
     * Note a multipart object's ETag is not an MD5 of its bytes, which matters to
     * anything comparing ETags (cloud verdict matching uses it only as an identity).
     */
    public StoredObjectMetadata upload(StorageLocation location, String objectKey, java.io.InputStream content,
                                        long contentLength, String contentType) {
        String bucket = requireBucket(location);
        try {
            PutObjectRequest.Builder request = PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(objectKey)
                    .contentLength(contentLength);
            if (contentType != null && !contentType.isBlank()) {
                request.contentType(contentType);
            }
            ServerSideEncryptionSetting sse = serverSideEncryption(location);
            if (sse != null) {
                request.serverSideEncryption(ServerSideEncryption.fromValue(sse.algorithm()));
                if (sse.kmsKeyId() != null) {
                    request.ssekmsKeyId(sse.kmsKeyId());
                }
            }
            PutObjectResponse response = client(location).putObject(request.build(),
                    RequestBody.fromInputStream(content, contentLength));
            return new StoredObjectMetadata(objectKey, contentLength, contentType,
                    stripQuotes(response.eTag()), OffsetDateTime.now());
        } catch (Exception e) {
            log.warn("S3 upload failed for object {} at storage location {}", objectKey, location.getStorageLocationId());
            throw new StorageUploadException(objectKey, e);
        }
    }

    @Override
    public boolean encryptsAtRest(StorageLocation location) {
        return serverSideEncryption(location) != null;
    }

    /** @param kmsKeyId null = the bucket's default KMS key (or SSE-S3) */
    record ServerSideEncryptionSetting(String algorithm, String kmsKeyId) {
    }

    /**
     * The location's own {@code configuration} ({"serverSideEncryption": "aws:kms", "kmsKeyId": "..."})
     * wins; otherwise the deployment setting for its purpose (dms.storage.staging/quarantine.*), then
     * dms.storage.s3.*. Null = no server-side encryption requested.
     */
    ServerSideEncryptionSetting serverSideEncryption(StorageLocation location) {
        String algorithm = null;
        String kmsKeyId = null;
        if (location.getConfiguration() != null && !location.getConfiguration().isBlank()) {
            try {
                var config = CONFIG_MAPPER.readTree(location.getConfiguration());
                algorithm = text(config.path("serverSideEncryption").asText(null));
                kmsKeyId = text(config.path("kmsKeyId").asText(null));
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new StorageConfigurationException("Storage location " + location.getStorageLocationId()
                        + " has a configuration that isn't valid JSON");
            }
        }
        if (algorithm == null && location.getPurpose() != null) {
            StorageProperties.PurposeTarget target = switch (location.getPurpose()) {
                case staging -> storageProperties.getStaging();
                case quarantine -> storageProperties.getQuarantine();
                default -> null;
            };
            if (target != null) {
                algorithm = text(target.getServerSideEncryption());
                kmsKeyId = text(target.getKmsKeyId());
            }
        }
        if (algorithm == null) {
            algorithm = text(storageProperties.getS3().getServerSideEncryption());
            kmsKeyId = text(storageProperties.getS3().getKmsKeyId());
        }
        if (algorithm == null || "none".equalsIgnoreCase(algorithm)) {
            return null;
        }
        if (!algorithm.equals("AES256") && !algorithm.equals("aws:kms")) {
            throw new StorageConfigurationException("Unknown S3 server-side encryption '" + algorithm
                    + "' - use AES256 or aws:kms");
        }
        return new ServerSideEncryptionSetting(algorithm, "aws:kms".equals(algorithm) ? kmsKeyId : null);
    }

    private static String text(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper CONFIG_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    @Override
    public DownloadedObject download(StorageLocation location, String objectKey) {
        String bucket = requireBucket(location);
        try {
            ResponseInputStream<GetObjectResponse> response = client(location)
                    .getObject(GetObjectRequest.builder().bucket(bucket).key(objectKey).build());
            GetObjectResponse metadata = response.response();
            return new DownloadedObject(response, metadata.contentLength() == null ? 0 : metadata.contentLength(),
                    metadata.contentType());
        } catch (NoSuchKeyException e) {
            throw new StorageObjectNotFoundException(objectKey);
        } catch (Exception e) {
            log.warn("S3 download failed for object {} at storage location {}", objectKey, location.getStorageLocationId());
            throw new StorageDownloadException(objectKey, e);
        }
    }

    @Override
    public void delete(StorageLocation location, String objectKey) {
        String bucket = requireBucket(location);
        try {
            client(location).deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(objectKey).build());
        } catch (Exception e) {
            log.warn("S3 delete failed for object {} at storage location {}", objectKey, location.getStorageLocationId());
            throw new StorageDeleteException(objectKey, e);
        }
    }

    @Override
    public boolean exists(StorageLocation location, String objectKey) {
        String bucket = requireBucket(location);
        try {
            client(location).headObject(HeadObjectRequest.builder().bucket(bucket).key(objectKey).build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return false;
            }
            throw new StorageDownloadException(objectKey, e);
        } catch (Exception e) {
            log.warn("S3 head-object failed for {} at storage location {}", objectKey, location.getStorageLocationId());
            throw new StorageDownloadException(objectKey, e);
        }
    }

    @Override
    public StoredObjectMetadata getMetadata(StorageLocation location, String objectKey) {
        String bucket = requireBucket(location);
        try {
            HeadObjectResponse response = client(location)
                    .headObject(HeadObjectRequest.builder().bucket(bucket).key(objectKey).build());
            OffsetDateTime lastModified = response.lastModified() == null
                    ? null
                    : response.lastModified().atOffset(ZoneOffset.UTC);
            return new StoredObjectMetadata(objectKey,
                    response.contentLength() == null ? 0 : response.contentLength(),
                    response.contentType(), stripQuotes(response.eTag()), lastModified);
        } catch (NoSuchKeyException e) {
            throw new StorageObjectNotFoundException(objectKey);
        } catch (Exception e) {
            log.warn("S3 head-object failed for {} at storage location {}", objectKey, location.getStorageLocationId());
            throw new StorageDownloadException(objectKey, e);
        }
    }

    @Override
    public Optional<String> generatePresignedDownloadUrl(StorageLocation location, String objectKey, Duration ttl) {
        String bucket = requireBucket(location);
        GetObjectRequest getObjectRequest = GetObjectRequest.builder().bucket(bucket).key(objectKey).build();
        PresignedGetObjectRequest presigned = presigner(location).presignGetObject(
                GetObjectPresignRequest.builder().signatureDuration(ttl).getObjectRequest(getObjectRequest).build());
        // Deliberately not logged - see this class's own doc comment.
        return Optional.of(presigned.url().toString());
    }

    // Package-private, same reason as the `clients`/`presigners` fields
    // above - lets S3ObjectStorageAdapterTest verify the endpointOverride()/
    // forcePathStyle() builder wiring succeeds without performing a real
    // S3 operation (which would require a live server).
    S3Client client(StorageLocation location) {
        return clients.computeIfAbsent(location.getStorageLocationId(), id -> {
            AwsCredentialsProvider credentials = resolveCredentials(location);
            S3ClientBuilder builder = S3Client.builder()
                    .region(resolveRegion(location))
                    .credentialsProvider(credentials)
                    .forcePathStyle(location.isPathStyleAccess());
            String endpoint = location.getEndpoint();
            if (endpoint != null && !endpoint.isBlank()) {
                builder.endpointOverride(URI.create(endpoint));
            }
            return builder.build();
        });
    }

    S3Presigner presigner(StorageLocation location) {
        return presigners.computeIfAbsent(location.getStorageLocationId(), id -> {
            AwsCredentialsProvider credentials = resolveCredentials(location);
            S3Presigner.Builder builder = S3Presigner.builder()
                    .region(resolveRegion(location))
                    .credentialsProvider(credentials);
            String endpoint = location.getEndpoint();
            if (endpoint != null && !endpoint.isBlank()) {
                builder.endpointOverride(URI.create(endpoint));
            }
            return builder.build();
        });
    }

    private Region resolveRegion(StorageLocation location) {
        String region = location.getRegion();
        if (region == null || region.isBlank()) {
            region = storageProperties.getS3().getRegion();
        }
        return Region.of(region);
    }

    private String requireBucket(StorageLocation location) {
        String bucket = location.getBucket();
        if (bucket == null || bucket.isBlank()) {
            bucket = storageProperties.getS3().getBucket();
        }
        if (bucket == null || bucket.isBlank()) {
            throw new StorageConfigurationException(
                    "Storage location " + location.getStorageLocationId() + " has no S3 bucket configured");
        }
        return bucket;
    }

    /**
     * {@code credentialRef} is never a raw key - it names an environment
     * variable pair ({@code STORAGE_CRED_<ref>_ACCESS_KEY}/{@code _SECRET_KEY})
     * this method resolves at call time. A blank ref falls back to this
     * deployment's single {@code dms.storage.s3.*} configuration, covering
     * both the platform-default location and any tenant content with the
     * deployment's one configured S3 backend.
     */
    private AwsCredentialsProvider resolveCredentials(StorageLocation location) {
        String ref = location.getCredentialRef();
        String accessKey;
        String secretKey;
        if (ref == null || ref.isBlank()) {
            accessKey = storageProperties.getS3().getAccessKey();
            secretKey = storageProperties.getS3().getSecretKey();
        } else {
            String prefix = "STORAGE_CRED_" + ref.toUpperCase(Locale.ROOT).replace('-', '_') + "_";
            accessKey = environment.getProperty(prefix + "ACCESS_KEY");
            secretKey = environment.getProperty(prefix + "SECRET_KEY");
        }
        if (accessKey == null || accessKey.isBlank() || secretKey == null || secretKey.isBlank()) {
            throw new StorageConfigurationException(
                    "No S3 credentials resolved for storage location " + location.getStorageLocationId()
                            + " (credentialRef=" + (ref == null || ref.isBlank() ? "<deployment-default>" : ref) + ")");
        }
        return StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));
    }

    private static String stripQuotes(String eTag) {
        return eTag == null ? null : eTag.replace("\"", "");
    }
}
