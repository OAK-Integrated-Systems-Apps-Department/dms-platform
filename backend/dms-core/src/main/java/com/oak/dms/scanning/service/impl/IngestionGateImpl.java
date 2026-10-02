package com.oak.dms.scanning.service.impl;

import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.scanning.enums.ScanMode;
import com.oak.dms.scanning.dto.DetectedFileType;
import com.oak.dms.scanning.dto.IngestionRequest;
import com.oak.dms.scanning.dto.IngestionTicketResponse;
import com.oak.dms.scanning.entity.ScanEngine;
import com.oak.dms.scanning.entity.ScanResult;
import com.oak.dms.scanning.enums.ScanResultStatus;
import com.oak.dms.scanning.exception.FileTooLargeException;
import com.oak.dms.scanning.helper.EncryptionMarkerInputStream;
import com.oak.dms.scanning.helper.FileTypeInspector;
import com.oak.dms.scanning.helper.IngestionTicketMapper;
import com.oak.dms.scanning.helper.ScanProperties;
import com.oak.dms.scanning.helper.SizeLimitingInputStream;
import com.oak.dms.scanning.helper.StorageObjectMover;
import com.oak.dms.scanning.repository.ScanResultRepository;
import com.oak.dms.scanning.service.DocumentPromoter;
import com.oak.dms.scanning.service.IngestionGate;
import com.oak.dms.scanning.service.ScanEngineFactory;
import com.oak.dms.scanning.service.ScanEngineResolver;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.storage.dto.StoredObjectMetadata;
import com.oak.dms.storage.entity.StorageLocation;
import com.oak.dms.storage.enums.StoragePurpose;
import com.oak.dms.storage.exception.StorageException;
import com.oak.dms.storage.service.ObjectStorageServiceFactory;
import com.oak.dms.storage.service.StorageLocationResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.UUID;

/**
 * See {@link IngestionGate}. The request-time half of the scan gate:
 * <ol>
 *   <li>resolve the tenant's engine and staging location, and check they are
 *   compatible - before a single upload byte is read;</li>
 *   <li>peek the head of the upload and check its type ({@link FileTypeInspector})
 *   - a disallowed type is rejected with nothing stored;</li>
 *   <li>record a {@code pending} scan result, already leased to this request;</li>
 *   <li>stream the upload into staging, computing its SHA-256 on the way with
 *   {@link DigestInputStream} (the technique {@code DocumentServiceImpl} used
 *   for direct uploads) and, for PDFs, watching for {@code /Encrypt} - all in
 *   one pass;</li>
 *   <li>hand the row to {@link IngestionProcessor} - which, for ClamAV,
 *   reaches a verdict before this request returns.</li>
 * </ol>
 * Never runs inside a transaction: each step commits on its own, so a crash
 * at any point leaves a row the retry scheduler can finish or fail.
 */
@Slf4j
@Service
public class IngestionGateImpl implements IngestionGate {

    private final ScanEngineResolver scanEngineResolver;
    private final ScanEngineFactory scanEngineFactory;
    private final StorageLocationResolver storageLocationResolver;
    private final ObjectStorageServiceFactory objectStorageServiceFactory;
    private final ScanResultRepository scanResultRepository;
    private final FileTypeInspector fileTypeInspector;
    private final IngestionProcessor ingestionProcessor;
    private final StorageObjectMover storageObjectMover;
    private final DocumentPromoter documentPromoter;
    private final IngestionTicketMapper ingestionTicketMapper;
    private final ScanProperties scanProperties;
    private final TransactionTemplate transactionTemplate;

    public IngestionGateImpl(ScanEngineResolver scanEngineResolver,
                             ScanEngineFactory scanEngineFactory,
                             StorageLocationResolver storageLocationResolver,
                             ObjectStorageServiceFactory objectStorageServiceFactory,
                             ScanResultRepository scanResultRepository,
                             FileTypeInspector fileTypeInspector,
                             IngestionProcessor ingestionProcessor,
                             StorageObjectMover storageObjectMover,
                             DocumentPromoter documentPromoter,
                             IngestionTicketMapper ingestionTicketMapper,
                             ScanProperties scanProperties,
                             PlatformTransactionManager transactionManager) {
        this.scanEngineResolver = scanEngineResolver;
        this.scanEngineFactory = scanEngineFactory;
        this.storageLocationResolver = storageLocationResolver;
        this.objectStorageServiceFactory = objectStorageServiceFactory;
        this.scanResultRepository = scanResultRepository;
        this.fileTypeInspector = fileTypeInspector;
        this.ingestionProcessor = ingestionProcessor;
        this.storageObjectMover = storageObjectMover;
        this.documentPromoter = documentPromoter;
        this.ingestionTicketMapper = ingestionTicketMapper;
        this.scanProperties = scanProperties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public IngestionTicketResponse submit(IngestionRequest request) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();

        // 1. Engine + staging, compatibility - fail before reading any bytes.
        ScanEngine engine = scanEngineResolver.resolveForTenant(tenantId);
        StorageLocation staging = storageLocationResolver.resolveForTenant(tenantId, StoragePurpose.staging);
        scanEngineFactory.resolveCompatible(engine, staging);

        long maxBytes = engine.getMaxFileSizeBytes();
        if (request.contentLength() > maxBytes) {
            throw new FileTooLargeException(maxBytes);
        }

        // 2. Type check on the head of the stream - nothing stored if it fails.
        BufferedInputStream buffered = new BufferedInputStream(request.content(), FileTypeInspector.HEAD_BYTES * 2);
        byte[] head = peek(buffered);
        DetectedFileType detected = fileTypeInspector.inspect(request.originalFilename(), head, head.length);

        // 3. The pending row, leased to this request so the scheduler leaves it alone.
        ScanResult row = createPendingRow(request, tenantId, userId, engine, staging, detected);
        UUID scanResultId = row.getScanResultId();
        OffsetDateTime leaseToken = row.getLeaseExpiresAt();

        if (detected.encrypted()) {
            ingestionProcessor.markUnscannable(scanResultId, leaseToken,
                    "Password-protected Office document (encrypted OOXML package)", null);
            return ticket(scanResultId);
        }

        // 4. Stage: size limit -> SHA-256 -> /Encrypt watch, one pass over the bytes.
        SizeLimitingInputStream limited = new SizeLimitingInputStream(buffered, maxBytes);
        MessageDigest digest = sha256();
        InputStream hashing = new DigestInputStream(limited, digest);
        EncryptionMarkerInputStream encryptionWatch = detected.pdf() ? new EncryptionMarkerInputStream(hashing) : null;
        StoredObjectMetadata stored;
        try {
            // Encrypted at rest like everything else. A cloud scanner (GuardDuty / Defender) reads the staged
            // object in place, so there the storage's own server-side encryption protects it instead - the
            // upload is refused if the staging location doesn't have it.
            stored = (engine.getScanMode() == ScanMode.post_store_object
                    ? objectStorageServiceFactory.resolveForInPlaceScan(staging)
                    : objectStorageServiceFactory.resolve(staging)).upload(staging, row.getStagingObjectKey(),
                    encryptionWatch != null ? encryptionWatch : hashing, request.contentLength(), detected.mimeType());
        } catch (StorageException e) {
            storageObjectMover.deleteQuietly(staging, row.getStagingObjectKey());
            if (limited.limitExceeded()) {
                ingestionProcessor.failTerminal(scanResultId, leaseToken,
                        "File exceeds the maximum upload size of " + maxBytes + " bytes");
                ingestionProcessor.purgeStaging(scanResultId);
                throw new FileTooLargeException(maxBytes);
            }
            ingestionProcessor.failTerminal(scanResultId, leaseToken, "Staging upload failed: " + e.getMessage());
            ingestionProcessor.purgeStaging(scanResultId);
            throw e;
        }
        String fileHash = HexFormat.of().formatHex(digest.digest());
        recordStaged(scanResultId, fileHash, limited.count(), stored.contentHash());

        if (encryptionWatch != null && encryptionWatch.markerFound()) {
            ingestionProcessor.markUnscannable(scanResultId, leaseToken, "Encrypted PDF", null);
            return ticket(scanResultId);
        }

        // 5. Scan and route. ClamAV answers now; cloud scanners come back pending.
        ingestionProcessor.process(scanResultId);
        return ticket(scanResultId);
    }

    @Override
    public IngestionTicketResponse getTicket(UUID scanResultId) {
        ScanResult row = scanResultRepository
                .findByScanResultIdAndTenantIdAndDeletedFalse(scanResultId, TenantContext.getTenantId())
                .filter(r -> r.getUploadedBy().equals(TenantContext.getUserId()))
                .orElseThrow(() -> new ResourceNotFoundException("IngestionTicket", scanResultId));
        return toTicket(row);
    }

    private ScanResult createPendingRow(IngestionRequest request, UUID tenantId, UUID userId, ScanEngine engine,
                                        StorageLocation staging, DetectedFileType detected) {
        return transactionTemplate.execute(status -> {
            // Microseconds: the lease value is this request's ownership token and
            // is later compared to what Postgres (timestamptz, micros) hands back.
            OffsetDateTime now = OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS);
            ScanResult saved = scanResultRepository.saveAndFlush(ScanResult.builder()
                    .tenantId(tenantId)
                    .scanEngineId(engine.getScanEngineId())
                    .stagingLocationId(staging.getStorageLocationId())
                    .originalFilename(request.originalFilename() != null ? request.originalFilename() : "upload")
                    .mimeType(detected.mimeType())
                    .status(ScanResultStatus.pending)
                    .scanMode(engine.getScanMode())
                    .startedAt(now)
                    .uploadedBy(userId)
                    .nextAttemptAt(now)
                    .leaseExpiresAt(now.plusSeconds(scanProperties.getRetry().getLeaseSeconds()))
                    .ingestionKind(request.kind())
                    .createdReason(request.createdReason())
                    .documentTypeId(request.documentTypeId())
                    .folderId(request.folderId())
                    .targetDocumentId(request.targetDocumentId())
                    .title(request.title())
                    .versionComment(request.versionComment())
                    .createdBy(userId)
                    .createdAt(now)
                    .build());
            if (!detected.encrypted()) {
                // Server-generated from UUIDs only - never the uploaded filename.
                saved.setStagingObjectKey("staging/" + tenantId + "/" + saved.getScanResultId());
                saved = scanResultRepository.saveAndFlush(saved);
            }
            return saved;
        });
    }

    private void recordStaged(UUID scanResultId, String fileHash, long sizeBytes, String eTag) {
        transactionTemplate.executeWithoutResult(status -> scanResultRepository.lockById(scanResultId)
                .ifPresent(locked -> {
                    locked.setFileHash(fileHash);
                    locked.setSizeBytes(sizeBytes);
                    // The staged object's ETag - what cloud verdicts are matched against.
                    locked.setExternalScanId(eTag);
                    locked.setUpdatedAt(OffsetDateTime.now());
                    scanResultRepository.save(locked);
                }));
    }

    private IngestionTicketResponse ticket(UUID scanResultId) {
        return toTicket(scanResultRepository.findById(scanResultId)
                .orElseThrow(() -> new ResourceNotFoundException("IngestionTicket", scanResultId)));
    }

    private IngestionTicketResponse toTicket(ScanResult row) {
        UUID documentId = row.getDocumentVersionId() == null
                ? null
                : documentPromoter.findDocumentIdForVersion(row.getTenantId(), row.getDocumentVersionId()).orElse(null);
        return ingestionTicketMapper.toTicket(row, documentId);
    }

    private static byte[] peek(BufferedInputStream in) {
        try {
            in.mark(FileTypeInspector.HEAD_BYTES);
            byte[] head = in.readNBytes(FileTypeInspector.HEAD_BYTES);
            in.reset();
            return head;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the uploaded file", e);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available on this JVM", e);
        }
    }
}
