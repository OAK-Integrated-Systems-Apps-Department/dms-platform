package com.oak.dms.importexport.export;

import com.oak.dms.common.exception.ApiException;
import com.oak.dms.document.Document;
import com.oak.dms.document.DocumentRepository;
import com.oak.dms.document.DocumentService;
import com.oak.dms.document.DocumentVersion;
import com.oak.dms.document.DocumentVersionRepository;
import com.oak.dms.document.dto.DownloadedDocument;
import com.oak.dms.folder.Folder;
import com.oak.dms.folder.FolderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes one download's ZIP to a local file - the documents' files only, as
 * Google Drive does:
 * <pre>
 *   Finance/Invoices/inv-1.pdf     a folder's download: the folder and its subfolders as they are
 *   inv-1.pdf, inv-1 (2).pdf       selected documents: side by side, same names numbered
 *   Files not included.txt         only when some file couldn't be added, and why
 * </pre>
 * Files are read through {@link DocumentService#downloadDocumentFromJob} -
 * the same tenant, scan-gate and servability checks and DOCUMENT_DOWNLOADED
 * audit as a single download. Access is applied when the download is
 * requested and again when it runs (its selection leaves out what was hidden
 * from the requester at either point - see ExportJobWorker), so a ZIP never
 * contains a file the requester couldn't download one by one; and it is
 * refused once they lose access to any of its documents. A document whose
 * file can't be served (not scanned clean, for one) is left out and named in
 * the note. Needs {@code TenantContext} bound to the requester.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExportArchiveBuilder {

    static final String SKIPPED_ENTRY = "Files not included.txt";
    private static final int CHUNK = 200;
    private static final int MAX_NAME = 150;

    private final DocumentRepository documentRepository;
    private final DocumentVersionRepository documentVersionRepository;
    private final FolderRepository folderRepository;
    private final DocumentService documentService;
    /** Unwraps a chunk's file keys in bulk first, instead of one key-service request per file. */
    private final com.oak.dms.encryption.KeyPrefetcher keyPrefetcher;

    /** @param filesSkipped documents whose file couldn't be included */
    public record Result(int documentCount, long filesBytes, int filesSkipped) {
    }

    /** Thrown when the files would take the download over its size limit. */
    public static class ExportTooLargeException extends RuntimeException {
        ExportTooLargeException(String message) {
            super(message);
        }
    }

    /**
     * @param rootFolderId the folder being downloaded - its name becomes the ZIP's top folder and
     *                     its subfolders are kept; null for a selection of documents (no folders)
     */
    public Result build(UUID tenantId, List<UUID> documentIds, UUID rootFolderId, Path target, long maxBytes) {
        Map<UUID, Folder> folders = rootFolderId == null ? Map.of()
                : folderRepository.findByTenantIdAndDeletedFalseOrderByFolderNameAsc(tenantId).stream()
                        .collect(Collectors.toMap(Folder::getFolderId, Function.identity()));
        Set<String> usedNames = new HashSet<>();
        List<String> notIncluded = new ArrayList<>();
        long filesBytes = 0;
        int count = 0;

        try (OutputStream out = Files.newOutputStream(target); ZipOutputStream zip = new ZipOutputStream(out)) {
            for (int from = 0; from < documentIds.size(); from += CHUNK) {
                List<UUID> chunk = documentIds.subList(from, Math.min(from + CHUNK, documentIds.size()));
                Map<UUID, Document> documents = documentRepository
                        .findAllByDocumentIdInAndTenantIdAndDeletedFalse(chunk, tenantId).stream()
                        .collect(Collectors.toMap(Document::getDocumentId, d -> d));
                keyPrefetcher.prefetchDocuments(documents.keySet());
                for (UUID documentId : chunk) {
                    Document document = documents.get(documentId);
                    if (document == null) {
                        continue; // deleted since the download was requested
                    }
                    count++;
                    DocumentVersion version = version(document, tenantId);
                    long size = version != null ? version.getSizeBytes() : 0;
                    if (filesBytes + size > maxBytes) {
                        throw new ExportTooLargeException("The files add up to more than the "
                                + (maxBytes / (1024 * 1024)) + " MB a download may hold. Download fewer documents.");
                    }
                    String entryName = entryName(directory(document.getFolderId(), rootFolderId, folders),
                            version, usedNames);
                    String why = addFile(zip, document.getDocumentId(), entryName);
                    if (why == null) {
                        filesBytes += size;
                    } else {
                        notIncluded.add(document.getTitle() + " (" + entryName + "): " + why);
                    }
                }
            }
            if (!notIncluded.isEmpty()) {
                zip.putNextEntry(new ZipEntry(SKIPPED_ENTRY));
                zip.write(("These files couldn't be added to the download:\r\n\r\n"
                        + String.join("\r\n", notIncluded) + "\r\n").getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write the download ZIP", e);
        }
        return new Result(count, filesBytes, notIncluded.size());
    }

    /** Streams one document's file into the ZIP; returns why it was left out, or null when added. */
    private String addFile(ZipOutputStream zip, UUID documentId, String entryName) throws IOException {
        DownloadedDocument downloaded;
        try {
            downloaded = documentService.downloadDocumentFromJob(documentId, "bulk_export");
        } catch (ApiException e) {
            return e.getMessage();
        }
        zip.putNextEntry(new ZipEntry(entryName));
        try (InputStream content = downloaded.content()) {
            content.transferTo(zip);
        } finally {
            zip.closeEntry();
        }
        return null;
    }

    private DocumentVersion version(Document document, UUID tenantId) {
        UUID versionId = document.getCurrentVersionId() != null ? document.getCurrentVersionId()
                : document.getLatestVersionId();
        return documentVersionRepository
                .findByVersionIdAndDocumentIdAndTenantIdAndDeletedFalse(versionId, document.getDocumentId(), tenantId)
                .orElse(null);
    }

    /**
     * "Finance/Invoices/" for a document in Finance › Invoices when Finance is downloaded - the
     * path from the downloaded folder down to the document's own folder. "" for a selection.
     */
    static String directory(UUID folderId, UUID rootFolderId, Map<UUID, Folder> folders) {
        if (rootFolderId == null) {
            return "";
        }
        List<String> names = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        for (UUID id = folderId; id != null && seen.add(id); ) {
            Folder folder = folders.get(id);
            if (folder == null) {
                break;
            }
            names.add(0, safeName(folder.getFolderName(), "Folder"));
            if (id.equals(rootFolderId)) {
                return String.join("/", names) + "/";
            }
            id = folder.getParentFolderId();
        }
        // Not under the root (shouldn't happen for a folder's download) - at the top folder, not lost.
        Folder root = folders.get(rootFolderId);
        return safeName(root != null ? root.getFolderName() : null, "Folder") + "/";
    }

    /** The file's own name inside {@code directory} - "name (2).pdf" when that name is taken there. */
    static String entryName(String directory, DocumentVersion version, Set<String> used) {
        String base = safeName(version != null ? version.getOriginalFilename() : null, "file");
        int dot = base.lastIndexOf('.');
        String stem = dot > 0 ? base.substring(0, dot) : base;
        String extension = dot > 0 ? base.substring(dot) : "";
        String name = directory + base;
        for (int n = 2; !used.add(name.toLowerCase(Locale.ROOT)); n++) {
            name = directory + stem + " (" + n + ")" + extension;
        }
        return name;
    }

    /** A single path segment: no folders smuggled in, no characters Windows or ZIP tools choke on. */
    static String safeName(String raw, String fallback) {
        String name = raw == null ? "" : raw.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}:*?\"<>|]", "_").trim();
        if (name.isEmpty() || name.equals(".") || name.equals("..")) {
            return fallback;
        }
        return name.length() > MAX_NAME ? name.substring(name.length() - MAX_NAME) : name;
    }
}
