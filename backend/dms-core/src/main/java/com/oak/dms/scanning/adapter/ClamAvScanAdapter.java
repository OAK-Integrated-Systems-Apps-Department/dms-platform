package com.oak.dms.scanning.adapter;

import com.oak.dms.scanning.dto.ScanTarget;
import com.oak.dms.scanning.dto.ScanVerdict;
import com.oak.dms.scanning.entity.ScanEngine;
import com.oak.dms.scanning.enums.ScanProvider;
import com.oak.dms.scanning.enums.ScanVerdictStatus;
import com.oak.dms.scanning.exception.ScanConfigurationException;
import com.oak.dms.scanning.exception.ScanEngineErrorException;
import com.oak.dms.scanning.exception.ScanException;
import com.oak.dms.scanning.helper.ScanProperties;
import com.oak.dms.scanning.service.ScanService;
import com.oak.dms.storage.dto.DownloadedObject;
import com.oak.dms.storage.enums.StorageProvider;
import com.oak.dms.storage.exception.StorageException;
import com.oak.dms.storage.service.ObjectStorageServiceFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ClamAV {@code clamd} over TCP ({@code INSTREAM}) - the on-premise,
 * {@code pre_store_stream} engine. Reads the staged object through
 * {@link ObjectStorageServiceFactory} (so staging can be any storage
 * provider), streams it to clamd, and returns the verdict synchronously.
 * <p>
 * Engine and signature version come from clamd's {@code VERSION} reply
 * ({@code ClamAV 1.4.3/27780/Mon Sep 28 08:24:02 2026} -> engine
 * {@code 1.4.3}, signatures {@code 27780 (Mon Sep 28 08:24:02 2026)}),
 * cached per engine for a few minutes rather than asked on every scan.
 * <p>
 * {@code Heuristics.Encrypted.*} detections (only raised if clamd's
 * {@code AlertEncrypted*} options are on) are mapped to UNSCANNABLE, not
 * INFECTED - an encrypted file isn't evidence of malware, it just can't be
 * inspected. So are {@code Heuristics.Limits.Exceeded.*} ({@code AlertExceedsMax}):
 * clamd stopped before the end - an archive nested too deep, too many files
 * inside - so what it didn't reach is unknown, not infected.
 * <p>
 * A file larger than {@code dms.scanning.clamav.segment-bytes} is scanned as
 * overlapping segments ({@link OverlappingSegments}), since clamd stops
 * reading one stream at 2147483645 bytes. Every byte is checked against the
 * byte-pattern signatures, and the overlap keeps a match that straddles a cut
 * whole. Segments after the first start mid-file, though, so file-format
 * parsing (a PDF's or an Office package's structure) only covers the first.
 * Any detection makes the file INFECTED; otherwise any segment clamd couldn't
 * finish makes it UNSCANNABLE.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClamAvScanAdapter implements ScanService {

    static final String ENGINE_NAME = "ClamAV";
    private static final Duration VERSION_CACHE_TTL = Duration.ofMinutes(5);
    private static final String ENCRYPTED_HEURISTIC_PREFIX = "Heuristics.Encrypted";
    private static final String LIMITS_HEURISTIC_PREFIX = "Heuristics.Limits.Exceeded";

    private final ScanProperties scanProperties;
    private final ObjectStorageServiceFactory objectStorageServiceFactory;

    // Package-private (not private) so a same-package test can seed a stub
    // ClamdClient for a given engine, bypassing client() below - mirrors the
    // storage adapters' client caches. Test seam only, not a public API.
    final ConcurrentHashMap<UUID, ClamdClient> clients = new ConcurrentHashMap<>();
    final ConcurrentHashMap<UUID, CachedVersion> versions = new ConcurrentHashMap<>();

    @Override
    public ScanProvider supportedProvider() {
        return ScanProvider.CLAMAV;
    }

    @Override
    public Optional<StorageProvider> requiredStagingProvider() {
        return Optional.empty();
    }

    @Override
    public ScanVerdict scan(ScanEngine engine, ScanTarget target) {
        ClamdClient client = client(engine);
        CachedVersion version = version(engine, client);
        ScanProperties.ClamAv settings = scanProperties.getClamav();

        // clamd reads at most 2147483645 bytes of one stream and answers OK for
        // the rest unread, so a larger file goes over in overlapping segments,
        // one INSTREAM each. A file that fits is one segment - one INSTREAM, as before.
        String unscannableThreat = null;
        String unscannableReply = null;
        InputStream content = openStaged(target);
        try {
            OverlappingSegments segments = new OverlappingSegments(content, settings.getSegmentBytes(),
                    settings.getSegmentOverlapBytes());
            InputStream segment;
            while ((segment = nextSegment(segments, target)) != null) {
                String reply = client.instream(segment);
                String body = reply.startsWith("stream:") ? reply.substring("stream:".length()).trim() : reply;
                if (body.equals("OK")) {
                    continue;
                }
                if (!body.endsWith(" FOUND")) {
                    throw new ScanEngineErrorException("clamd returned no verdict for scan result "
                            + target.scanResultId() + ": " + reply);
                }
                String threat = body.substring(0, body.length() - " FOUND".length()).trim();
                if (!threat.startsWith(ENCRYPTED_HEURISTIC_PREFIX) && !threat.startsWith(LIMITS_HEURISTIC_PREFIX)) {
                    return new ScanVerdict(ScanVerdictStatus.INFECTED, threat, null, ENGINE_NAME,
                            version.engineVersion(), version.signatureVersion(), OffsetDateTime.now(), reply);
                }
                // Part of the file can't be inspected - but a later segment may still hold a
                // detection, which outranks that, so keep going.
                if (unscannableThreat == null) {
                    unscannableThreat = threat;
                    unscannableReply = reply;
                }
            }
        } finally {
            try {
                content.close();
            } catch (IOException e) {
                // The verdict (if any) is already in hand - a failed close must not discard it.
                log.debug("Closing staged object stream for scan result {} failed", target.scanResultId(), e);
            }
        }

        OffsetDateTime scannedAt = OffsetDateTime.now();
        if (unscannableThreat != null) {
            return new ScanVerdict(ScanVerdictStatus.UNSCANNABLE, unscannableThreat, null, ENGINE_NAME,
                    version.engineVersion(), version.signatureVersion(), scannedAt, unscannableReply);
        }
        return new ScanVerdict(ScanVerdictStatus.CLEAN, null, null, ENGINE_NAME, version.engineVersion(),
                version.signatureVersion(), scannedAt, null);
    }

    private static InputStream nextSegment(OverlappingSegments segments, ScanTarget target) {
        try {
            return segments.next();
        } catch (IOException e) {
            throw new ScanEngineErrorException("Could not read the staged object for scan result "
                    + target.scanResultId() + " to scan it", e);
        }
    }

    private InputStream openStaged(ScanTarget target) {
        try {
            DownloadedObject staged = objectStorageServiceFactory.resolve(target.stagingLocation())
                    .download(target.stagingLocation(), target.stagingObjectKey());
            return staged.content();
        } catch (StorageException e) {
            throw new ScanEngineErrorException("Could not read staged object for scan result "
                    + target.scanResultId() + ": " + e.getMessage(), e);
        }
    }

    // Package-private for the same test-seam reason as `clients`.
    ClamdClient client(ScanEngine engine) {
        return clients.computeIfAbsent(engine.getScanEngineId(), id -> {
            ScanProperties.ClamAv defaults = scanProperties.getClamav();
            String host = defaults.getHost();
            int port = defaults.getPort();
            String endpoint = engine.getEndpoint();
            if (endpoint != null && !endpoint.isBlank()) {
                String hostPort = endpoint.replaceFirst("^tcp://", "");
                int colon = hostPort.lastIndexOf(':');
                if (colon <= 0) {
                    throw new ScanConfigurationException("Scan engine " + id + " endpoint must be host:port, got "
                            + endpoint);
                }
                host = hostPort.substring(0, colon);
                try {
                    port = Integer.parseInt(hostPort.substring(colon + 1));
                } catch (NumberFormatException e) {
                    throw new ScanConfigurationException("Scan engine " + id + " endpoint has an invalid port: "
                            + endpoint);
                }
            }
            return new ClamdClient(host, port, defaults.getConnectTimeoutMs(),
                    (int) Duration.ofSeconds(engine.getTimeoutSeconds()).toMillis(), defaults.getChunkSizeBytes());
        });
    }

    private CachedVersion version(ScanEngine engine, ClamdClient client) {
        CachedVersion cached = versions.get(engine.getScanEngineId());
        if (cached != null && cached.fetchedAt().plus(VERSION_CACHE_TTL).isAfter(Instant.now())) {
            return cached;
        }
        CachedVersion fresh;
        try {
            fresh = parseVersion(client.version());
        } catch (ScanException e) {
            if (cached != null) {
                return cached;
            }
            throw e;
        }
        versions.put(engine.getScanEngineId(), fresh);
        return fresh;
    }

    /** {@code ClamAV 1.4.3/27780/Mon Sep 28 08:24:02 2026} -> ("1.4.3", "27780 (Mon Sep 28 08:24:02 2026)"). */
    static CachedVersion parseVersion(String reply) {
        String body = reply.startsWith(ENGINE_NAME + " ") ? reply.substring(ENGINE_NAME.length() + 1) : reply;
        String[] parts = body.split("/", 3);
        String engineVersion = parts[0].trim();
        String signatureVersion = parts.length >= 3
                ? parts[1].trim() + " (" + parts[2].trim() + ")"
                : parts.length == 2 ? parts[1].trim() : null;
        return new CachedVersion(engineVersion, signatureVersion, Instant.now());
    }

    record CachedVersion(String engineVersion, String signatureVersion, Instant fetchedAt) {
    }
}
