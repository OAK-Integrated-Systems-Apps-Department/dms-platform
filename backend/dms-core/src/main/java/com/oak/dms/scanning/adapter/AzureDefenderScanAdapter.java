package com.oak.dms.scanning.adapter;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.models.BlobProperties;
import com.azure.storage.blob.models.BlobStorageException;
import com.azure.storage.common.StorageSharedKeyCredential;
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

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Microsoft Defender for Storage malware scanning - an Azure,
 * {@code post_store_object} engine. Defender's on-upload scanning scans a
 * blob when it is written to a protected storage account, so there is no
 * per-object submit call: <b>writing the upload into the staging container
 * is the submission</b>. The verdict is then read from the blob index tags
 * Defender writes onto the scanned blob ({@code Malware Scanning scan result}
 * / {@code Malware Scanning scan time UTC}), via the existing
 * {@code azure-storage-blob} SDK - no extra dependency. {@link #scan} returns
 * {@code PENDING} until the tag appears.
 * <p>
 * Also parses Defender's Event Grid notifications
 * ({@code Microsoft.Security.MalwareScanningResult}) for the callback
 * endpoint - which only makes the row due for a re-check; the tag stays the
 * source of truth. The threat name is only available from that event
 * (tags don't carry it).
 * <p>
 * Recorded engine info: {@code engine_name = Microsoft Defender for Storage};
 * Defender exposes no engine or signature version, so both are null.
 * <p>
 * Deployment prerequisites: Defender for Storage with on-upload malware
 * scanning enabled on the staging account, "blob index tags" scan results
 * enabled, and (for callbacks) an Event Grid custom topic subscription to
 * {@code /api/v1/scanning/callbacks/azure-defender} delivering the
 * {@code X-Oak-Scan-Callback-Secret} header - see docs/scanning-architecture.md.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AzureDefenderScanAdapter implements ScanService, ScanCallbackParser {

    static final String ENGINE_NAME = "Microsoft Defender for Storage";
    static final String RESULT_TAG = "Malware Scanning scan result";
    static final String TIME_TAG = "Malware Scanning scan time UTC";
    static final String RESULT_CLEAN = "No threats found";
    static final String RESULT_MALICIOUS = "Malicious";
    private static final String VALIDATION_EVENT = "Microsoft.EventGrid.SubscriptionValidationEvent";
    private static final String RESULT_EVENT = "Microsoft.Security.MalwareScanningResult";

    private final ScanProperties scanProperties;
    private final StorageProperties storageProperties;
    private final Environment environment;
    private final ObjectMapper objectMapper;

    // Package-private test seam, same as the storage adapters' client caches:
    // one client per (engine, staging location).
    final ConcurrentHashMap<ClientKey, BlobServiceClient> clients = new ConcurrentHashMap<>();

    record ClientKey(UUID scanEngineId, UUID stagingLocationId) {
    }

    @Override
    public ScanProvider supportedProvider() {
        return ScanProvider.AZURE_DEFENDER;
    }

    @Override
    public Optional<StorageProvider> requiredStagingProvider() {
        return Optional.of(StorageProvider.AZURE_BLOB);
    }

    @Override
    public ScanVerdict scan(ScanEngine engine, ScanTarget target) {
        StorageLocation staging = target.stagingLocation();
        try {
            BlobClient blob = client(engine, staging)
                    .getBlobContainerClient(requireContainer(staging))
                    .getBlobClient(target.stagingObjectKey());

            BlobProperties properties = blob.getProperties();
            String eTag = stripQuotes(properties.getETag());
            if (target.expectedETag() != null && !target.expectedETag().equalsIgnoreCase(eTag)) {
                throw new ScanEngineErrorException("Staged blob for scan result " + target.scanResultId()
                        + " changed since it was staged (ETag mismatch)");
            }

            Map<String, String> tags = blob.getTags();
            String result = tags.get(RESULT_TAG);
            if (result == null || result.isBlank()) {
                return ScanVerdict.pending(ENGINE_NAME);
            }
            OffsetDateTime scannedAt = parseTime(tags.get(TIME_TAG));
            if (RESULT_CLEAN.equalsIgnoreCase(result.trim())) {
                return new ScanVerdict(ScanVerdictStatus.CLEAN, null, eTag, ENGINE_NAME, null, null, scannedAt, result);
            }
            if (RESULT_MALICIOUS.equalsIgnoreCase(result.trim())) {
                return new ScanVerdict(ScanVerdictStatus.INFECTED, target.hintedThreatName(), eTag, ENGINE_NAME,
                        null, null, scannedAt, result);
            }
            // "Error", "Not scanned", ... - no verdict; retried, then failed. Never promoted.
            throw new ScanEngineErrorException("Defender for Storage reported \"" + result + "\" for scan result "
                    + target.scanResultId());
        } catch (ScanException e) {
            throw e;
        } catch (BlobStorageException e) {
            if (e.getStatusCode() == 401 || e.getStatusCode() == 403) {
                throw new ScanConfigurationException("Defender scan engine " + engine.getScanEngineId()
                        + " is not authorised to read blob tags on the staging container (HTTP " + e.getStatusCode() + ")");
            }
            throw new ScanEngineErrorException("Reading Defender verdict failed (HTTP " + e.getStatusCode() + ")", e);
        } catch (RuntimeException e) {
            throw new ScanEngineUnavailableException("Azure Blob Storage unreachable while reading Defender verdict: "
                    + e.getMessage(), e);
        }
    }

    @Override
    public String callbackSecret() {
        return scanProperties.getAzureDefender().getCallbackSecret();
    }

    /**
     * Accepts the Event Grid schema (a JSON array) or CloudEvents (a single
     * object, or an array). Anything not a validation or malware-result event
     * is ignored.
     */
    @Override
    public ScanCallbackParseResult parse(String body) {
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (Exception e) {
            log.warn("Unparseable Defender callback body ignored");
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
            String type = event.path("eventType").asText(event.path("type").asText(""));
            JsonNode data = event.path("data");
            if (VALIDATION_EVENT.equals(type)) {
                String code = data.path("validationCode").asText("");
                return ScanCallbackParseResult.handshake(
                        objectMapper.createObjectNode().put("validationResponse", code).toString());
            }
            if (!RESULT_EVENT.equals(type)) {
                continue;
            }
            BlobRef blob = parseBlobUri(data.path("blobUri").asText(null));
            if (blob == null) {
                continue;
            }
            List<String> threats = new ArrayList<>();
            data.path("scanResultDetails").path("malwareNamesFound").forEach(name -> threats.add(name.asText()));
            results.add(new ScanCallbackEvent(blob.container(), blob.key(), stripQuotes(data.path("eTag").asText(null)),
                    threats, data.path("correlationId").asText(event.path("id").asText(null))));
        }
        return ScanCallbackParseResult.events(results);
    }

    private BlobServiceClient client(ScanEngine engine, StorageLocation staging) {
        return clients.computeIfAbsent(new ClientKey(engine.getScanEngineId(), staging.getStorageLocationId()),
                key -> new BlobServiceClientBuilder()
                        .endpoint(requireEndpoint(engine, staging))
                        .credential(resolveCredentials(engine))
                        .buildClient());
    }

    private String requireEndpoint(ScanEngine engine, StorageLocation staging) {
        for (String candidate : new String[]{engine.getEndpoint(), scanProperties.getAzureDefender().getEndpoint(),
                staging.getEndpoint(), storageProperties.getAzureBlob().getEndpoint()}) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        throw new ScanConfigurationException("Defender scan engine " + engine.getScanEngineId()
                + " has no Blob Service endpoint configured");
    }

    private String requireContainer(StorageLocation staging) {
        String container = staging.getBucket();
        if (container == null || container.isBlank()) {
            container = storageProperties.getAzureBlob().getContainer();
        }
        if (container == null || container.isBlank()) {
            throw new ScanConfigurationException("Staging location " + staging.getStorageLocationId()
                    + " has no Azure Blob container configured");
        }
        return container;
    }

    /**
     * {@code credentialRef} is never a raw key - it names
     * {@code SCAN_CRED_<ref>_ACCOUNT_NAME}/{@code _ACCOUNT_KEY}, resolved at
     * call time; blank falls back to {@code dms.scanning.azure-defender.*}.
     * Never logged.
     */
    private StorageSharedKeyCredential resolveCredentials(ScanEngine engine) {
        String ref = engine.getCredentialRef();
        String accountName;
        String accountKey;
        if (ref == null || ref.isBlank()) {
            accountName = scanProperties.getAzureDefender().getAccountName();
            accountKey = scanProperties.getAzureDefender().getAccountKey();
        } else {
            String prefix = "SCAN_CRED_" + ref.toUpperCase(Locale.ROOT).replace('-', '_') + "_";
            accountName = environment.getProperty(prefix + "ACCOUNT_NAME");
            accountKey = environment.getProperty(prefix + "ACCOUNT_KEY");
        }
        if (accountName == null || accountName.isBlank() || accountKey == null || accountKey.isBlank()) {
            throw new ScanConfigurationException("No Azure credentials resolved for Defender scan engine "
                    + engine.getScanEngineId() + " (credentialRef="
                    + (ref == null || ref.isBlank() ? "<deployment-default>" : ref) + ")");
        }
        return new StorageSharedKeyCredential(accountName, accountKey);
    }

    record BlobRef(String container, String key) {
    }

    /** {@code https://<account>.blob.core.windows.net/<container>/<key...>} -> (container, key). */
    static BlobRef parseBlobUri(String blobUri) {
        if (blobUri == null || blobUri.isBlank()) {
            return null;
        }
        try {
            String path = URI.create(blobUri).getRawPath();
            if (path == null || path.length() < 2) {
                return null;
            }
            String trimmed = path.substring(1);
            int slash = trimmed.indexOf('/');
            if (slash <= 0 || slash == trimmed.length() - 1) {
                return null;
            }
            return new BlobRef(URLDecoder.decode(trimmed.substring(0, slash), StandardCharsets.UTF_8),
                    URLDecoder.decode(trimmed.substring(slash + 1), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static OffsetDateTime parseTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value.trim().replace(' ', 'T'));
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String stripQuotes(String eTag) {
        return eTag == null ? null : eTag.replace("\"", "");
    }
}
