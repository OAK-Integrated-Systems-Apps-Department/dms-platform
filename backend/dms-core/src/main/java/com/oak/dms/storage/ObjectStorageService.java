package com.oak.dms.storage;

import com.oak.dms.common.exception.UpstreamServiceException;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;

/**
 * Writes source document bytes to the same MinIO bucket ocr-worker reads
 * from (minio.source-bucket must equal ocr-worker's MINIO_SOURCE_BUCKET --
 * see backend/workers/ocr-worker/src/ocr_worker/storage.py).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ObjectStorageService {

    private final MinioClient minioClient;
    private final MinioProperties properties;

    @PostConstruct
    void ensureBucketExists() {
        try {
            boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(properties.getSourceBucket()).build());
            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(properties.getSourceBucket()).build());
            }
        } catch (Exception e) {
            // Same reasoning as ocr-worker's own storage.ensure_buckets(): a
            // transient MinIO outage at boot shouldn't take dms-core down --
            // the first real upload surfaces it loudly instead (as a real
            // UpstreamServiceException/502, not silently).
            log.warn("Failed to ensure MinIO bucket '{}' exists at startup: {}", properties.getSourceBucket(), e.getMessage());
        }
    }

    public void putSourceObject(String objectKey, byte[] content, String contentType) {
        try {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(properties.getSourceBucket())
                    .object(objectKey)
                    .stream(new ByteArrayInputStream(content), content.length, -1)
                    .contentType(contentType != null ? contentType : "application/octet-stream")
                    .build());
        } catch (Exception e) {
            throw new UpstreamServiceException("Failed to upload object to MinIO: " + objectKey, e);
        }
    }
}
