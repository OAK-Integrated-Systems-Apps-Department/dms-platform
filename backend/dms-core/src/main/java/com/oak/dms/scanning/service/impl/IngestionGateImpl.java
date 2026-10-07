package com.oak.dms.scanning.service.impl;

import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.scanning.enums.ScanMode;
import com.oak.dms.scanning.dto.DetectedFileType;
import com.oak.dms.scanning.dto.IngestionRequest;
import com.oak.dms.scanning.dto.IngestionTicketResponse;
import com.oak.dms.scanning.entity.ScanEngine;
import com.oak.dms.scanning.entity.ScanResult;
import com.oak.dms.scanning.enums.ScanProvider;
import com.oak.dms.scanning.enums.ScanResultStatus;
import com.oak.dms.scanning.exception.FileTooLargeException;
import com.oak.dms.scanning.exception.FileTypeNotAllowedException;
import com.oak.dms.scanning.helper.EncryptionMarkerInputStream;
import com.oak.dms.scanning.helper.FileTypeInspector;
import com.oak.dms.scanning.helper.IngestionProperties;
import com.oak.dms.scanning.helper.IngestionTicketMapper;
import com.oak.dms.scanning.helper.ScanProperties;
import com.oak.dms.scanning.helper.SizeLimitingInputStream;
import com.oak.dms.scanning.helper.StorageObjectMover;
import com.oak.dms.scanning.helper.ZipArchiveCheck;
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
import com.oak.dms.storage.service.ObjectStorageService;
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
 *   reaches a verdict before this request returns (except for a file larger
 *   than one clamd segment, left pending for the retry scheduler).</li>
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
    private final IngestionProperties ingestionProperties;
    private final TransactionTemplate transactionTemplate;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

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
                             IngestionProperties ingestionProperties,
                             PlatformTransactionManager transactionManager,
                             org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.jdbc = jdbc;
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
        this.ingestionProperties = ingestionProperties;
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
                    "zip".equals(detected.extension()) ? "Password-protected ZIP archive"
                            : "Password-protected Office document (encrypted OOXML package)", null);
            return ticket(scanResultId);
        }

        // 4. Stage: size limit -> SHA-256 -> /Encrypt watch, one pass over the bytes.
        SizeLimitingInputStream limited = new SizeLimitingInputStream(buffered, maxBytes);
        MessageDigest digest = sha256();
        InputStream hashing = new DigestInputStream(limited, digest);
        EncryptionMarkerInputStream encryptionWatch = detected.pdf() ? new EncryptionMarkerInputStream(hashing) : null;
        StoredObjectMetadata stored;
        ObjectStorageService stagingStore = engine.getScanMode() == ScanMode.post_store_object
                ? objectStorageServiceFactory.resolveForInPlaceScan(staging)
                : objectStorageServiceFactory.resolve(staging);
        try {
            // Encrypted at rest like everything else. A cloud scanner (GuardDuty / Defender) reads the staged
            // object in place, so there the storage's own server-side encryption protects it instead - the
            // upload is refused if the staging location doesn't have it.
            stored = stagingStore.upload(staging, row.getStagingObjectKey(),
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

        // 4a. A ZIP is checked whole - complete, readable, within what the scanner can inspect - by
        // reading the staged copy once. Nothing is extracted or run; the archive is kept as uploaded.
        boolean largeArchive = false;
        if ("zip".equals(detected.extension())) {
            ZipArchiveCheck.Result zip;
            try (InputStream staged = stagingStore.download(staging, row.getStagingObjectKey()).content()) {
                zip = ZipArchiveCheck.check(staged, ingestionProperties.getZipMaxEntries(),
                        ingestionProperties.getZipMaxUncompressedBytes());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            if (zip.outcome() == ZipArchiveCheck.Outcome.ENCRYPTED) {
                ingestionProcessor.markUnscannable(scanResultId, leaseToken, zip.message(), null);
                return ticket(scanResultId);
            }
            if (zip.outcome() == ZipArchiveCheck.Outcome.INVALID) {
                ingestionProcessor.failTerminal(scanResultId, leaseToken, zip.message());
                ingestionProcessor.purgeStaging(scanResultId);
                throw new FileTypeNotAllowedException(zip.message());
            }
            // More than clamd opens of one archive: ClamAvScanAdapter scans it file by file instead.
            largeArchive = zip.uncompressedBytes() > scanProperties.getClamav().getArchiveScanBytes()
                    || zip.entries() > scanProperties.getClamav().getArchiveScanFiles();
        }

        // 4a'. An Office Open XML file's head only showed a plausible package (its parts can come in
        // any order); its index must list [Content_Types].xml, or it's a ZIP wearing an Office extension.
        // An index that can't be read is left to the scanner, as before this check.
        if (FileTypeInspector.isOfficeOpenXml(detected.extension())) {
            ZipArchiveCheck.Presence types;
            try (InputStream staged = stagingStore.download(staging, row.getStagingObjectKey()).content()) {
                types = ZipArchiveCheck.containsEntry(staged, FileTypeInspector.OOXML_CONTENT_TYPES);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            if (types == ZipArchiveCheck.Presence.ABSENT) {
                String message = "The file's content does not match its ." + detected.extension() + " extension";
                ingestionProcessor.failTerminal(scanResultId, leaseToken, message);
                ingestionProcessor.purgeStaging(scanResultId);
                throw new FileTypeNotAllowedException(message);
            }
        }

        // 4b. Duplicates - now that the content's fingerprint is known, before anything is scanned or stored.
        // The uploader sending the same file into the same folder again - typically because a large
        // one is still being scanned and they weren't sure it went through - gets their first upload's
        // ticket back, to follow that scan, rather than a refusal blaming "someone".
        java.util.Optional<UUID> earlier = ownEarlierUpload(request, tenantId, userId, scanResultId,
                row.getCreatedAt(), fileHash);
        if (earlier.isPresent()) {
            ingestionProcessor.failTerminal(scanResultId, leaseToken,
                    "Same file as upload " + earlier.get() + " by the same user - that one is followed instead");
            ingestionProcessor.purgeStaging(scanResultId);
            return ticket(earlier.get());
        }
        duplicateRefusal(request, tenantId, scanResultId, row.getCreatedAt(), fileHash).ifPresent(refusal -> {
            ingestionProcessor.failTerminal(scanResultId, leaseToken, refusal.getMessage());
            ingestionProcessor.purgeStaging(scanResultId);
            throw refusal;
        });

        // 5. Scan and route. ClamAV answers now; cloud scanners come back pending. So does a
        // file too large for one clamd pass, or a ZIP too large for clamd to open whole: scanning
        // it piece by piece can take far longer than a request should stay open, so the
        // scheduler picks it up straight away instead.
        if (engine.getProvider() == ScanProvider.CLAMAV
                && (largeArchive || limited.count() > scanProperties.getClamav().getSegmentBytes())) {
            ingestionProcessor.handOff(scanResultId, leaseToken);
            return ticket(scanResultId);
        }
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
                    .leaseExpiresAt(now.plusSeconds(scanProperties.getRetry().leaseSecondsFor(request.contentLength())))
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

    /**
     * The same user's own upload of the same file into the same folder within the last hour, still
     * being scanned or already through - newest first. Only for a new document whose uploader checked
     * for duplicates ("keep both" uploads deliberately add another copy).
     */
    private java.util.Optional<UUID> ownEarlierUpload(IngestionRequest request, UUID tenantId, UUID userId,
                                                      UUID scanResultId, OffsetDateTime createdAt, String fileHash) {
        if (request.kind() != com.oak.dms.scanning.enums.IngestionKind.new_document || !request.refuseDuplicates()) {
            return java.util.Optional.empty();
        }
        return jdbc.queryForList("""
                select s.scan_result_id from scan_results s
                 where s.tenant_id = ? and s.uploaded_by = ? and s.file_hash = ? and s.scan_result_id <> ?
                   and s.ingestion_kind = 'new_document' and s.folder_id is not distinct from ?
                   and s.status in ('pending', 'clean', 'released_override') and not s.is_deleted
                   and s.created_at > now() - interval '1 hour'
                   and (s.created_at, s.scan_result_id) < (?, ?)
                   -- Through already: only while the document it became is still there.
                   and (s.status = 'pending' or exists (
                        select 1 from document_versions v join documents d on d.document_id = v.document_id
                         where v.version_id = s.document_version_id and not v.is_deleted and not d.is_deleted))
                 order by s.created_at desc
                 limit 1
                """, UUID.class, tenantId, userId, fileHash, scanResultId, request.folderId(), createdAt,
                scanResultId).stream().findFirst();
    }

    /**
     * A new version byte-for-byte equal to its document's current version adds nothing - refused. A new
     * document whose uploader checked for duplicates is refused when the same file is already in that folder,
     * or someone uploaded it there a moment earlier (both checked and found nothing - the earlier upload wins,
     * so exactly one of them goes through).
     */
    private java.util.Optional<com.oak.dms.common.exception.DuplicateUploadException> duplicateRefusal(
            IngestionRequest request, UUID tenantId, UUID scanResultId, OffsetDateTime createdAt, String fileHash) {
        if (request.kind() == com.oak.dms.scanning.enums.IngestionKind.new_version) {
            Boolean same = jdbc.queryForObject("""
                    select coalesce((select v.file_hash = ? from documents d
                        join document_versions v on v.version_id = coalesce(d.current_version_id, d.latest_version_id)
                        where d.document_id = ? and d.tenant_id = ?), false)
                    """, Boolean.class, fileHash, request.targetDocumentId(), tenantId);
            return Boolean.TRUE.equals(same)
                    ? java.util.Optional.of(new com.oak.dms.common.exception.DuplicateUploadException(
                    "VERSION_IDENTICAL", "This file is identical to the current version - there's nothing new to add."))
                    : java.util.Optional.empty();
        }
        if (!request.refuseDuplicates()) {
            return java.util.Optional.empty();
        }
        Boolean taken = jdbc.queryForObject("""
                select exists (select 1 from documents d
                               join document_versions v on v.document_id = d.document_id and not v.is_deleted
                               where d.tenant_id = ? and not d.is_deleted and not d.is_template
                                 and d.folder_id is not distinct from ? and v.file_hash = ?)
                    or exists (select 1 from scan_results s
                               where s.tenant_id = ? and s.file_hash = ? and s.scan_result_id <> ?
                                 and s.ingestion_kind = 'new_document' and s.folder_id is not distinct from ?
                                 and s.status in ('pending', 'clean', 'released_override')
                                 and s.document_version_id is null and not s.is_deleted
                                 and s.created_at > now() - interval '1 hour'
                                 and (s.created_at, s.scan_result_id) < (?, ?))
                """, Boolean.class, tenantId, request.folderId(), fileHash, tenantId, fileHash, scanResultId,
                request.folderId(), createdAt, scanResultId);
        return Boolean.TRUE.equals(taken)
                ? java.util.Optional.of(new com.oak.dms.common.exception.DuplicateUploadException(
                "DUPLICATE_UPLOADED_MEANWHILE", "The same file was just added to this folder - check again "
                + "and choose what to do with it."))
                : java.util.Optional.empty();
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
