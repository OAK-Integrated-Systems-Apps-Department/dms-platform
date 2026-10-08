package com.oak.dms.importexport.service;

import com.oak.dms.common.exception.ApiException;
import com.oak.dms.importexport.config.ImportExportProperties;
import com.oak.dms.importexport.dto.ImportArchiveReceivedResponse;
import com.oak.dms.importexport.dto.ImportArchiveReceivedResponse.EntryProblem;
import com.oak.dms.importexport.dto.ImportFileReceivedResponse;
import com.oak.dms.importexport.exception.BulkImportException;
import com.oak.dms.importexport.exception.ImportBatchStateException;
import com.oak.dms.importexport.exception.ImportFileAlreadyReceivedException;
import com.oak.dms.importexport.exception.ImportFileNotInBatchException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * Receives a bulk import's files as one ZIP instead of one by one. Each
 * entry is handed to {@link BulkImportService#receiveFile} exactly as a
 * single upload would be - matched to its row by name, type-checked,
 * counted against the batch size cap, staged - one transaction per entry,
 * so a bad entry only skips that entry. The ZIP itself is never stored or
 * ingested: the platform accepts no archives as documents
 * (docs/scanning-architecture.md), and every file inside still goes
 * through the scan gate on its own after submit. A ZIP inside the ZIP is
 * refused by the type check like any other archive.
 * <p>
 * Entry names are only used to match rows - nothing is written to disk
 * under them. Folders, macOS metadata ({@code __MACOSX/}, {@code ._*}) and
 * {@code .DS_Store} are skipped.
 * <p>
 * Not transactional itself, so each {@code receiveFile} call commits on its
 * own (and holds the batch lock only for that one entry).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImportArchiveIntake {

    /** Problems listed back to the caller; the rest are only counted. */
    static final int MAX_LISTED_PROBLEMS = 200;

    private final BulkImportService bulkImportService;
    private final UploadSessionService uploadSessionService;
    private final ImportExportProperties properties;

    /** A ZIP sent in the request itself (within the multipart limit). */
    public ImportArchiveReceivedResponse receive(UUID batchId, MultipartFile archive) {
        if (archive == null || archive.isEmpty()) {
            throw BulkImportException.badRequest("ARCHIVE_MISSING", "Attach the ZIP of the files.");
        }
        requireZipName(archive.getOriginalFilename());
        requireWithinLimit(archive.getSize());
        Path temp = null;
        try {
            temp = Files.createTempFile("dms-import-", ".zip");
            archive.transferTo(temp);
            return unpack(batchId, temp);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not receive the ZIP", e);
        } finally {
            UploadSessionService.deleteQuietly(temp);
        }
    }

    /** A ZIP that arrived through a completed resumable upload session; the session is used up. */
    public ImportArchiveReceivedResponse receiveFromSession(UUID batchId, UUID uploadSessionId) {
        // Fail on a batch that can't take files before using the session up.
        requireUploading(batchId);
        Path file = uploadSessionService.takeCompletedFile(uploadSessionId);
        try {
            requireWithinLimit(Files.size(file));
            return unpack(batchId, file);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the uploaded ZIP", e);
        } finally {
            UploadSessionService.deleteQuietly(file);
        }
    }

    ImportArchiveReceivedResponse unpack(UUID batchId, Path zipPath) {
        requireUploading(batchId);
        int entriesRead = 0;
        int matched = 0;
        int problemCount = 0;
        List<EntryProblem> problems = new ArrayList<>();
        ImportFileReceivedResponse last = null;

        try (ZipFile zip = open(zipPath)) {
            if (zip.size() > properties.getMaxArchiveEntries()) {
                throw BulkImportException.badRequest("ARCHIVE_TOO_MANY_ENTRIES",
                        "The ZIP holds more than " + properties.getMaxArchiveEntries() + " entries.");
            }
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = baseName(entry.getName());
                if (skipped(entry, name)) {
                    continue;
                }
                entriesRead++;
                String problem;
                if (entry.getSize() < 0) {
                    problemCount++;
                    if (problems.size() < MAX_LISTED_PROBLEMS) {
                        problems.add(new EntryProblem(entry.getName(), "Its size isn't recorded in the ZIP - skipped."));
                    }
                    continue;
                }
                try (InputStream content = zip.getInputStream(entry)) {
                    last = bulkImportService.receiveFile(batchId, name, null, entry.getSize(), content);
                    matched++;
                    continue;
                } catch (ImportBatchStateException e) {
                    // Submitted or cancelled meanwhile - nothing more can be added.
                    throw e;
                } catch (ImportFileNotInBatchException e) {
                    problem = "Not listed in the template - skipped.";
                } catch (ImportFileAlreadyReceivedException e) {
                    problem = "Already uploaded for every row that lists it - skipped.";
                } catch (ApiException e) {
                    problem = e.getMessage();
                } catch (IOException | RuntimeException e) {
                    log.warn("Bulk import batch {}: could not read ZIP entry {}", batchId, entry.getName(), e);
                    problem = "Could not be read from the ZIP.";
                }
                problemCount++;
                if (problems.size() < MAX_LISTED_PROBLEMS) {
                    problems.add(new EntryProblem(entry.getName(), problem));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the ZIP", e);
        }

        var status = bulkImportService.getStatus(batchId);
        log.info("Bulk import batch {}: ZIP gave {} of {} entries to rows ({} problems)", batchId, matched,
                entriesRead, problemCount);
        return new ImportArchiveReceivedResponse(entriesRead, matched, problemCount, problems,
                last != null ? last.filesReceived() : status.filesReceived(), status.totalRecords());
    }

    private void requireUploading(UUID batchId) {
        String status = bulkImportService.getStatus(batchId).status();
        if (!"uploading".equals(status)) {
            throw new ImportBatchStateException("This import has already been submitted (status: " + status + ").");
        }
    }

    private void requireWithinLimit(long size) {
        if (size > properties.getMaxArchiveSize().toBytes()) {
            throw new BulkImportException(HttpStatus.CONTENT_TOO_LARGE, "ARCHIVE_TOO_LARGE",
                    "The ZIP is larger than the " + properties.getMaxArchiveSize().toMegabytes() + " MB limit.");
        }
    }

    private static void requireZipName(String filename) {
        if (filename == null || !filename.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            throw BulkImportException.badRequest("ARCHIVE_NOT_ZIP", "Upload the files as a .zip archive.");
        }
    }

    private static boolean skipped(ZipEntry entry, String name) {
        String path = entry.getName().replace('\\', '/');
        return entry.isDirectory() || name.isEmpty() || path.startsWith("__MACOSX/") || path.contains("/__MACOSX/")
                || name.startsWith("._") || name.equals(".DS_Store");
    }

    static String baseName(String entryName) {
        String path = entryName.replace('\\', '/');
        return path.substring(path.lastIndexOf('/') + 1).trim();
    }

    /** UTF-8 names first; ZIPs from older Windows tools use the DOS code page. */
    private static ZipFile open(Path path) throws IOException {
        for (Charset charset : List.of(StandardCharsets.UTF_8, Charset.forName("IBM437"))) {
            ZipFile zip = null;
            try {
                zip = new ZipFile(path.toFile(), charset);
                zip.stream().forEach(e -> { }); // decode every name now
                return zip;
            } catch (IllegalArgumentException malformedName) {
                if (zip != null) {
                    zip.close();
                }
            } catch (ZipException e) {
                break;
            }
        }
        throw BulkImportException.badRequest("ARCHIVE_INVALID", "The uploaded file isn't a valid ZIP archive.");
    }
}
