package com.oak.dms.importexport.service;

import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.importexport.config.ImportExportProperties;
import com.oak.dms.importexport.dto.CreateUploadSessionRequest;
import com.oak.dms.importexport.dto.UploadSessionResponse;
import com.oak.dms.importexport.entity.UploadSession;
import com.oak.dms.importexport.enums.UploadSessionStatus;
import com.oak.dms.importexport.exception.BulkImportException;
import com.oak.dms.importexport.repository.UploadSessionRepository;
import com.oak.dms.security.auth.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.UUID;

/**
 * Resumable, chunked upload of a large ZIP for a bulk import (up to
 * {@code dms.import-export.max-archive-size}, in chunks of at most
 * {@code dms.import-export.max-chunk-size}). The client creates a session,
 * sends chunks in order ({@code offset} = bytes received so far), and after
 * a dropped connection asks for the session and carries on from
 * {@code receivedBytes}. A completed session is then unpacked into its
 * batch by {@code ImportArchiveIntake} - see {@link #takeCompletedFile}.
 * <p>
 * A chunk is first received into its own temporary file with no lock held
 * (that part runs at the client's network speed); only the fast local
 * append happens under the session's row lock.
 */
@Slf4j
@Service
public class UploadSessionService {

    private final UploadSessionRepository repository;
    private final ImportExportProperties properties;
    private final TransactionTemplate transactionTemplate;

    public UploadSessionService(UploadSessionRepository repository, ImportExportProperties properties,
                                PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.properties = properties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public UploadSessionResponse create(CreateUploadSessionRequest request) {
        if (!request.fileName().toLowerCase(Locale.ROOT).endsWith(".zip")) {
            throw BulkImportException.badRequest("INVALID_ARCHIVE", "Only .zip files can be uploaded this way.");
        }
        if (request.totalSizeBytes() > properties.getMaxArchiveSize().toBytes()) {
            throw new BulkImportException(HttpStatus.CONTENT_TOO_LARGE, "ARCHIVE_TOO_LARGE",
                    "The ZIP is larger than the " + properties.getMaxArchiveSize().toMegabytes() + " MB limit.");
        }
        OffsetDateTime now = OffsetDateTime.now();
        UploadSession session = repository.saveAndFlush(UploadSession.builder()
                .tenantId(TenantContext.getTenantId())
                .initiatedBy(TenantContext.getUserId())
                .createdBy(TenantContext.getUserId())
                .originalFilename(request.fileName())
                .totalSizeBytes(request.totalSizeBytes())
                .receivedBytes(0)
                .expiresAt(now.plus(properties.getUploadSessionTtl()))
                .createdAt(now)
                .build());
        Path file = sessionFile(session.getUploadSessionId());
        try {
            Files.createDirectories(file.getParent());
            Files.deleteIfExists(file);
            Files.createFile(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not create upload session file " + file, e);
        }
        return toResponse(session);
    }

    public UploadSessionResponse get(UUID sessionId) {
        return toResponse(own(sessionId));
    }

    /**
     * @param offset        where this chunk starts - must equal the bytes received so far
     * @param contentLength the chunk's exact length (the request's Content-Length)
     */
    public UploadSessionResponse appendChunk(UUID sessionId, long offset, long contentLength, InputStream body) {
        UploadSession session = own(sessionId);
        checkAcceptsChunk(session, offset, contentLength);

        Path file = sessionFile(sessionId);
        Path chunk = file.resolveSibling(sessionId + "." + UUID.randomUUID() + ".chunk");
        try {
            long received = copyExactly(body, chunk, contentLength);
            if (received != contentLength) {
                throw BulkImportException.badRequest("INCOMPLETE_CHUNK", "The chunk ended after " + received
                        + " of " + contentLength + " bytes - send it again from offset " + offset + ".");
            }
            return transactionTemplate.execute(status -> {
                UploadSession locked = repository.lockById(sessionId).orElseThrow();
                checkAcceptsChunk(locked, offset, contentLength); // a concurrent chunk may have landed first
                append(file, chunk, offset);
                OffsetDateTime now = OffsetDateTime.now();
                locked.setReceivedBytes(offset + contentLength);
                locked.setStatus(locked.getReceivedBytes() == locked.getTotalSizeBytes()
                        ? UploadSessionStatus.completed : UploadSessionStatus.uploading);
                locked.setUpdatedAt(now);
                locked.setUpdatedBy(TenantContext.getUserId());
                return toResponse(locked);
            });
        } finally {
            try {
                Files.deleteIfExists(chunk);
            } catch (IOException ignored) {
                // swept with the session
            }
        }
    }

    public void abort(UUID sessionId) {
        transactionTemplate.executeWithoutResult(status -> {
            UploadSession session = own(sessionId);
            session.setDeleted(true);
            session.setDeletedAt(OffsetDateTime.now());
            session.setStatus(UploadSessionStatus.failed);
            repository.save(session);
        });
        deleteQuietly(sessionFile(sessionId));
    }

    private void checkAcceptsChunk(UploadSession session, long offset, long contentLength) {
        if (session.getStatus() == UploadSessionStatus.completed) {
            throw BulkImportException.conflict("UPLOAD_COMPLETE", "Every byte of this upload has already been received.");
        }
        if (session.getStatus() == UploadSessionStatus.failed || session.getExpiresAt().isBefore(OffsetDateTime.now())) {
            throw BulkImportException.conflict("UPLOAD_EXPIRED", "This upload has expired - start it again.");
        }
        if (offset != session.getReceivedBytes()) {
            throw BulkImportException.conflict("UPLOAD_OFFSET_MISMATCH", "Expected the chunk at offset "
                    + session.getReceivedBytes() + ", not " + offset + ". Resume from " + session.getReceivedBytes() + ".");
        }
        if (contentLength <= 0) {
            throw BulkImportException.badRequest("EMPTY_CHUNK", "Send the chunk's Content-Length, and at least one byte.");
        }
        if (contentLength > properties.getMaxChunkSize().toBytes()) {
            throw new BulkImportException(HttpStatus.CONTENT_TOO_LARGE, "CHUNK_TOO_LARGE",
                    "A chunk may be at most " + properties.getMaxChunkSize().toBytes() + " bytes.");
        }
        if (offset + contentLength > session.getTotalSizeBytes()) {
            throw BulkImportException.badRequest("CHUNK_PAST_END", "The chunk goes past the file's declared size of "
                    + session.getTotalSizeBytes() + " bytes.");
        }
    }

    /** Copies at most {@code limit} bytes; returns how many arrived (more than limit counts as limit + 1). */
    private static long copyExactly(InputStream body, Path target, long limit) {
        try (OutputStream out = Files.newOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            int read;
            while ((read = body.read(buffer)) != -1) {
                total += read;
                if (total > limit) {
                    throw BulkImportException.badRequest("CHUNK_LENGTH_MISMATCH",
                            "The chunk is longer than its Content-Length.");
                }
                out.write(buffer, 0, read);
            }
            return total;
        } catch (IOException e) {
            throw BulkImportException.badRequest("INCOMPLETE_CHUNK",
                    "The chunk didn't arrive completely - send it again.");
        }
    }

    /** Writes the chunk at exactly {@code offset}, dropping anything a failed earlier attempt left past it. */
    private static void append(Path file, Path chunk, long offset) {
        try (FileChannel target = FileChannel.open(file, StandardOpenOption.WRITE);
             FileChannel source = FileChannel.open(chunk, StandardOpenOption.READ)) {
            target.truncate(offset);
            long size = source.size();
            long position = 0;
            while (position < size) {
                position += source.transferTo(position, size - position, target.position(offset + position));
            }
            target.force(false);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not store upload chunk for " + file, e);
        }
    }

    private UploadSession own(UUID sessionId) {
        return repository.findByUploadSessionIdAndTenantIdAndInitiatedByAndDeletedFalse(sessionId,
                        TenantContext.getTenantId(), TenantContext.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException("UploadSession", sessionId));
    }

    private UploadSessionResponse toResponse(UploadSession s) {
        return new UploadSessionResponse(s.getUploadSessionId(), s.getOriginalFilename(), s.getTotalSizeBytes(),
                s.getReceivedBytes(), s.getStatus(), properties.getMaxChunkSize().toBytes(), s.getExpiresAt());
    }

    /**
     * Hands over a completed session's file and closes the session, so it
     * can only ever be used once. The caller owns (and deletes) the file.
     */
    public Path takeCompletedFile(UUID sessionId) {
        Path file = sessionFile(sessionId);
        transactionTemplate.executeWithoutResult(status -> {
            UploadSession session = own(sessionId);
            UploadSession locked = repository.lockById(session.getUploadSessionId()).orElseThrow();
            if (locked.isDeleted() || locked.getStatus() != UploadSessionStatus.completed || !Files.isRegularFile(file)) {
                throw BulkImportException.conflict("UPLOAD_INCOMPLETE", "The ZIP upload hasn't finished yet ("
                        + locked.getReceivedBytes() + " of " + locked.getTotalSizeBytes() + " bytes received).");
            }
            OffsetDateTime now = OffsetDateTime.now();
            locked.setDeleted(true);
            locked.setDeletedAt(now);
            locked.setUpdatedAt(now);
            locked.setUpdatedBy(TenantContext.getUserId());
        });
        return file;
    }

    /** Deletes sessions nobody finished (or used) before their expiry, with what they received. */
    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT10M")
    public void expireSessions() {
        for (UploadSession session : repository.findByDeletedFalseAndExpiresAtBefore(OffsetDateTime.now())) {
            deleteQuietly(sessionFile(session.getUploadSessionId()));
            transactionTemplate.executeWithoutResult(status -> repository.lockById(session.getUploadSessionId())
                    .ifPresent(locked -> {
                        locked.setDeleted(true);
                        locked.setDeletedAt(OffsetDateTime.now());
                        locked.setStatus(UploadSessionStatus.failed);
                    }));
        }
    }

    /** Server-chosen path from the session id only - never the uploaded name. */
    Path sessionFile(UUID sessionId) {
        return Path.of(properties.getUploadSessionDir()).toAbsolutePath().normalize().resolve(sessionId + ".part");
    }

    static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("Could not delete upload session file {}: {}", file, e.getMessage());
        }
    }
}
