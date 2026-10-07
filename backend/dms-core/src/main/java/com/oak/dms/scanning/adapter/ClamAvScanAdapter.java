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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Enumeration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

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
 * <p>
 * A ZIP that unpacks to more than clamd opens of one archive (its MaxScanSize,
 * {@code dms.scanning.clamav.archive-scan-bytes}) is opened here and scanned
 * file by file instead - see {@link #scanZip}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClamAvScanAdapter implements ScanService {

    static final String ENGINE_NAME = "ClamAV";
    private static final Duration VERSION_CACHE_TTL = Duration.ofMinutes(5);
    private static final String ENCRYPTED_HEURISTIC_PREFIX = "Heuristics.Encrypted";
    private static final String LIMITS_HEURISTIC_PREFIX = "Heuristics.Limits.Exceeded";
    private static final String ZIP_MIME_TYPE = "application/zip";

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

        Findings findings = new Findings();
        if (ZIP_MIME_TYPE.equals(target.mimeType())) {
            scanZip(client, target, findings);
        } else {
            InputStream content = openStaged(target);
            try {
                scanSegments(client, content, target, findings, false);
            } finally {
                closeQuietly(content, target);
            }
        }

        OffsetDateTime scannedAt = OffsetDateTime.now();
        if (findings.infectedThreat != null) {
            return new ScanVerdict(ScanVerdictStatus.INFECTED, findings.infectedThreat, null, ENGINE_NAME,
                    version.engineVersion(), version.signatureVersion(), scannedAt, findings.infectedReply);
        }
        if (findings.unscannableThreat != null) {
            return new ScanVerdict(ScanVerdictStatus.UNSCANNABLE, findings.unscannableThreat, null, ENGINE_NAME,
                    version.engineVersion(), version.signatureVersion(), scannedAt, findings.unscannableReply);
        }
        return new ScanVerdict(ScanVerdictStatus.CLEAN, null, null, ENGINE_NAME, version.engineVersion(),
                version.signatureVersion(), scannedAt, null);
    }

    /**
     * A ZIP clamd can take whole - one segment, within its MaxFiles and MaxScanSize - goes
     * over as it is and clamd opens it, as before. A larger one would come back
     * {@code Heuristics.Limits.Exceeded} (unscannable), so it is opened here instead, by its
     * central directory, and every file in it is scanned as its own stream - segmented if large.
     * Nested archives inside are still opened by clamd. The archive's own bytes get a pass first,
     * for anything outside its entries; a limits alert there is expected (the segments are partial
     * archives, or too big to unpack) and ignored, since the contents are checked entry by entry.
     * Nothing is written out but a private temporary copy of the archive, deleted straight after.
     */
    private void scanZip(ClamdClient client, ScanTarget target, Findings findings) {
        ScanProperties.ClamAv settings = scanProperties.getClamav();
        Path copy = null;
        try {
            copy = Files.createTempFile("dms-zip-scan-", ".zip");
            InputStream staged = openStaged(target);
            try {
                Files.copy(staged, copy, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                closeQuietly(staged, target);
            }

            try (ZipFile zip = new ZipFile(copy.toFile())) {
                boolean fitsWhole = Files.size(copy) <= settings.getSegmentBytes()
                        && zip.size() <= settings.getArchiveScanFiles()
                        && unpackedSize(zip) <= settings.getArchiveScanBytes();
                try (InputStream raw = Files.newInputStream(copy)) {
                    if (scanSegments(client, raw, target, findings, !fitsWhole) || fitsWhole) {
                        return;
                    }
                }
                for (Enumeration<? extends ZipEntry> all = zip.entries(); all.hasMoreElements(); ) {
                    ZipEntry entry = all.nextElement();
                    if (entry.isDirectory()) {
                        continue;
                    }
                    InputStream data;
                    try {
                        data = zip.getInputStream(entry);
                    } catch (ZipException e) {
                        // A password-protected entry, or one compressed some way that can't be read.
                        findings.unscannable(ENCRYPTED_HEURISTIC_PREFIX + ".Zip", e.getMessage());
                        continue;
                    }
                    try (data) {
                        if (scanSegments(client, data, target, findings, false)) {
                            return;
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new ScanEngineErrorException("Could not read the staged ZIP for scan result "
                    + target.scanResultId() + " to scan it: " + e.getMessage(), e);
        } finally {
            if (copy != null) {
                try {
                    Files.deleteIfExists(copy);
                } catch (IOException e) {
                    log.debug("Deleting the temporary ZIP copy for scan result {} failed", target.scanResultId(), e);
                }
            }
        }
    }

    /** What the entries come to unpacked, as the archive's index records it; an unknown size counts as too large. */
    private static long unpackedSize(ZipFile zip) {
        long total = 0;
        for (Enumeration<? extends ZipEntry> all = zip.entries(); all.hasMoreElements(); ) {
            long size = all.nextElement().getSize();
            if (size < 0 || total + size < total) {
                return Long.MAX_VALUE;
            }
            total += size;
        }
        return total;
    }

    /**
     * Streams {@code content} to clamd in overlapping segments, since clamd reads at most
     * 2147483645 bytes of one stream and answers OK for the rest unread. Content that fits is one
     * segment - one INSTREAM. Returns true once something is detected (no need to go on). A part
     * clamd couldn't inspect is noted, but a later detection outranks it, so it keeps going.
     * {@code ignoreLimits}: a limits alert is expected here and what it skipped is checked elsewhere.
     */
    private boolean scanSegments(ClamdClient client, InputStream content, ScanTarget target, Findings findings,
                                 boolean ignoreLimits) {
        ScanProperties.ClamAv settings = scanProperties.getClamav();
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
            if (threat.startsWith(LIMITS_HEURISTIC_PREFIX)) {
                if (!ignoreLimits) {
                    findings.unscannable(threat, reply);
                }
            } else if (threat.startsWith(ENCRYPTED_HEURISTIC_PREFIX)) {
                findings.unscannable(threat, reply);
            } else {
                findings.infectedThreat = threat;
                findings.infectedReply = reply;
                return true;
            }
        }
        return false;
    }

    private static void closeQuietly(InputStream content, ScanTarget target) {
        try {
            content.close();
        } catch (IOException e) {
            // The verdict (if any) is already in hand - a failed close must not discard it.
            log.debug("Closing staged object stream for scan result {} failed", target.scanResultId(), e);
        }
    }

    /** The first detection, and the first part clamd couldn't inspect - a detection outranks it. */
    private static final class Findings {
        private String infectedThreat;
        private String infectedReply;
        private String unscannableThreat;
        private String unscannableReply;

        void unscannable(String threat, String reply) {
            if (unscannableThreat == null) {
                unscannableThreat = threat;
                unscannableReply = reply;
            }
        }
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
