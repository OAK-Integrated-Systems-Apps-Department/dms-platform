package com.oak.dms.scanning.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oak.dms.scanning.dto.ScanCallbackEvent;
import com.oak.dms.scanning.dto.ScanCallbackParseResult;
import com.oak.dms.scanning.dto.ScanTarget;
import com.oak.dms.scanning.dto.ScanVerdict;
import com.oak.dms.scanning.entity.ScanEngine;
import com.oak.dms.scanning.enums.ScanProvider;
import com.oak.dms.scanning.enums.ScanVerdictStatus;
import com.oak.dms.scanning.exception.ScanConfigurationException;
import com.oak.dms.scanning.exception.ScanEngineErrorException;
import com.oak.dms.scanning.exception.ScanEngineUnavailableException;
import com.oak.dms.scanning.exception.ScanException;
import com.oak.dms.scanning.helper.ScanProperties;
import com.oak.dms.scanning.service.ScanCallbackParser;
import com.oak.dms.scanning.service.ScanService;
import com.oak.dms.storage.entity.StorageLocation;
import com.oak.dms.storage.enums.StorageProvider;
import com.oak.dms.storage.helper.StorageProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.GetObjectTaggingRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.Tag;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AWS GuardDuty Malware Protection for S3 - a {@code post_store_object}
 * engine. GuardDuty scans an object when it is written to a bucket that has
 * a Malware Protection plan, so there is no per-object submit call:
 * <b>writing the upload into the staging bucket is the submission</b>. The
 * verdict is read from the {@code GuardDutyMalwareScanStatus} object tag
 * GuardDuty writes (plan option "tag scanned objects"), via the existing
 * {@code s3} SDK - no {@code guardduty} SDK dependency. {@link #scan}
 * returns {@code PENDING} until the tag appears.
 * <p>
 * Tag values: {@code NO_THREATS_FOUND} -> CLEAN, {@code THREATS_FOUND} ->
 * INFECTED, {@code UNSUPPORTED} (which includes password-protected files) ->
 * UNSCANNABLE, {@code ACCESS_DENIED}/{@code FAILED} -> engine error (retried,
 * then failed - never promoted).
 * <p>
 * Also parses the EventBridge "GuardDuty Malware Protection Object Scan
 * Result" event for the callback endpoint - the only place the threat name
 * appears (the tag doesn't carry it). A callback only makes the row due for
 * a re-check; the tag stays the source of truth.
 * <p>
 * Recorded engine info: {@code engine_name = Amazon GuardDuty Malware
 * Protection for S3}; GuardDuty exposes no engine or signature version, so
 * both are null.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AwsGuardDutyScanAdapter implements ScanService, ScanCallbackParser {

    static final String ENGINE_NAME = "Amazon GuardDuty Malware Protection for S3";
    static final String STATUS_TAG = "GuardDutyMalwareScanStatus";
    private static final String RESULT_DETAIL_TYPE = "GuardDuty Malware Protection Object Scan Result";

    private final ScanProperties scanProperties;
    private final StorageProperties storageProperties;
    private final Environment environment;
    private final ObjectMapper objectMapper;

    // Package-private test seam: one client per (engine, staging location).
    final ConcurrentHashMap<ClientKey, S3Client> clients = new ConcurrentHashMap<>();

    record ClientKey(UUID scanEngineId, UUID stagingLocationId) {
    }

    @Override
    public ScanProvider supportedProvider() {
        return ScanProvider.AWS_GUARDDUTY;
    }

    @Override
    public Optional<StorageProvider> requiredStagingProvider() {
        return Optional.of(StorageProvider.S3);
    }

    @Override
    public ScanVerdict scan(ScanEngine engine, ScanTarget target) {
        StorageLocation staging = target.stagingLocation();
        String bucket = requireBucket(staging);
        S3Client s3 = client(engine, staging);
        try {
            String eTag = stripQuotes(s3.headObject(HeadObjectRequest.builder()
                    .bucket(bucket).key(target.stagingObjectKey()).build()).eTag());
            if (target.expectedETag() != null && !target.expectedETag().equalsIgnoreCase(eTag)) {
                throw new ScanEngineErrorException("Staged object for scan result " + target.scanResultId()
                        + " changed since it was staged (ETag mismatch)");
            }

            String status = s3.getObjectTagging(GetObjectTaggingRequest.builder()
                            .bucket(bucket).key(target.stagingObjectKey()).build())
                    .tagSet().stream()
                    .filter(tag -> STATUS_TAG.equals(tag.key()))
                    .map(Tag::value)
                    .findFirst()
                    .orElse(null);
            if (status == null || status.isBlank()) {
                return ScanVerdict.pending(ENGINE_NAME);
            }
            return switch (status.trim().toUpperCase(Locale.ROOT)) {
                case "NO_THREATS_FOUND" -> new ScanVerdict(ScanVerdictStatus.CLEAN, null, eTag, ENGINE_NAME,
                        null, null, null, status);
                case "THREATS_FOUND" -> new ScanVerdict(ScanVerdictStatus.INFECTED, target.hintedThreatName(), eTag,
                        ENGINE_NAME, null, null, null, status);
                case "UNSUPPORTED" -> new ScanVerdict(ScanVerdictStatus.UNSCANNABLE,
                        "GuardDuty could not scan this file (UNSUPPORTED - e.g. password-protected)", eTag,
                        ENGINE_NAME, null, null, null, status);
                default -> throw new ScanEngineErrorException("GuardDuty reported " + status + " for scan result "
                        + target.scanResultId());
            };
        } catch (ScanException e) {
            throw e;
        } catch (S3Exception e) {
            if (e.statusCode() == 401 || e.statusCode() == 403) {
                throw new ScanConfigurationException("GuardDuty scan engine " + engine.getScanEngineId()
                        + " is not authorised to read tags in the staging bucket (HTTP " + e.statusCode() + ")");
            }
            throw new ScanEngineErrorException("Reading GuardDuty verdict failed (HTTP " + e.statusCode() + ")", e);
        } catch (SdkClientException e) {
            throw new ScanEngineUnavailableException("S3 unreachable while reading GuardDuty verdict: "
                    + e.getMessage(), e);
        }
    }

    @Override
    public String callbackSecret() {
        return scanProperties.getAwsGuardduty().getCallbackSecret();
    }

    @Override
    public ScanCallbackParseResult parse(String body) {
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (Exception e) {
            log.warn("Unparseable GuardDuty callback body ignored");
            return ScanCallbackParseResult.events(List.of());
        }
        List<JsonNode> events = new ArrayList<>();
        if (root.isArray()) {
            root.forEach(events::add);
        } else if (root.isObject()) {
            events.add(root);
        }

        List<ScanCallbackEvent> results = new ArrayList<>();
        for (JsonNode event : events) {
            if (!RESULT_DETAIL_TYPE.equals(event.path("detail-type").asText(""))) {
                continue;
            }
            JsonNode detail = event.path("detail");
            JsonNode object = detail.path("s3ObjectDetails");
            String bucket = object.path("bucketName").asText(null);
            String key = object.path("objectKey").asText(null);
            if (bucket == null || key == null) {
                continue;
            }
            List<String> threats = new ArrayList<>();
            detail.path("scanResultDetails").path("threats").forEach(threat -> threats.add(threat.path("name").asText()));
            results.add(new ScanCallbackEvent(bucket, key, stripQuotes(object.path("eTag").asText(null)), threats,
                    event.path("id").asText(null)));
        }
        return ScanCallbackParseResult.events(results);
    }

    private S3Client client(ScanEngine engine, StorageLocation staging) {
        return clients.computeIfAbsent(new ClientKey(engine.getScanEngineId(), staging.getStorageLocationId()), key -> {
            S3ClientBuilder builder = S3Client.builder()
                    .region(resolveRegion(engine, staging))
                    .credentialsProvider(resolveCredentials(engine))
                    .forcePathStyle(staging.isPathStyleAccess());
            String endpoint = staging.getEndpoint();
            if (endpoint != null && !endpoint.isBlank()) {
                builder.endpointOverride(URI.create(endpoint));
            }
            return builder.build();
        });
    }

    private Region resolveRegion(ScanEngine engine, StorageLocation staging) {
        for (String candidate : new String[]{engine.getRegion(), staging.getRegion(),
                scanProperties.getAwsGuardduty().getRegion()}) {
            if (candidate != null && !candidate.isBlank()) {
                return Region.of(candidate);
            }
        }
        return Region.US_EAST_1;
    }

    private String requireBucket(StorageLocation staging) {
        String bucket = staging.getBucket();
        if (bucket == null || bucket.isBlank()) {
            bucket = storageProperties.getS3().getBucket();
        }
        if (bucket == null || bucket.isBlank()) {
            throw new ScanConfigurationException("Staging location " + staging.getStorageLocationId()
                    + " has no S3 bucket configured");
        }
        return bucket;
    }

    /**
     * {@code credentialRef} is never a raw key - it names
     * {@code SCAN_CRED_<ref>_ACCESS_KEY}/{@code _SECRET_KEY}, resolved at call
     * time. Blank falls back to {@code dms.scanning.aws-guardduty.*}, and if
     * that is blank too, to the AWS default credentials chain (instance/pod
     * role) - the normal setup when dms-core itself runs on AWS. Never logged.
     */
    private AwsCredentialsProvider resolveCredentials(ScanEngine engine) {
        String ref = engine.getCredentialRef();
        String accessKey;
        String secretKey;
        if (ref == null || ref.isBlank()) {
            accessKey = scanProperties.getAwsGuardduty().getAccessKey();
            secretKey = scanProperties.getAwsGuardduty().getSecretKey();
            if ((accessKey == null || accessKey.isBlank()) && (secretKey == null || secretKey.isBlank())) {
                return DefaultCredentialsProvider.builder().build();
            }
        } else {
            String prefix = "SCAN_CRED_" + ref.toUpperCase(Locale.ROOT).replace('-', '_') + "_";
            accessKey = environment.getProperty(prefix + "ACCESS_KEY");
            secretKey = environment.getProperty(prefix + "SECRET_KEY");
        }
        if (accessKey == null || accessKey.isBlank() || secretKey == null || secretKey.isBlank()) {
            throw new ScanConfigurationException("No AWS credentials resolved for GuardDuty scan engine "
                    + engine.getScanEngineId() + " (credentialRef="
                    + (ref == null || ref.isBlank() ? "<deployment-default>" : ref) + ")");
        }
        return StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));
    }

    private static String stripQuotes(String eTag) {
        return eTag == null ? null : eTag.replace("\"", "");
    }
}
