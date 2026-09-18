package com.oak.dms.storage;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Deliberately mirrors backend/workers/ocr-worker/src/ocr_worker/config.py's
 * MINIO_* naming (endpoint/access-key/secret-key/source-bucket) -- dms-core
 * writes the original file here; ocr-worker reads it back from the same
 * bucket/key, so the two services' bucket config must agree.
 */
@Component
@ConfigurationProperties(prefix = "minio")
@Getter
@Setter
public class MinioProperties {
    private String endpoint;
    private String accessKey;
    private String secretKey;
    private String sourceBucket;
}
