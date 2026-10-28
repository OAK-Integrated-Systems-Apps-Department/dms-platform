package com.oak.dms.importexport.export;

import com.oak.dms.document.Document;
import com.oak.dms.document.DocumentRepository;
import com.oak.dms.folder.FolderAccessMode;
import com.oak.dms.folder.FolderRepository;
import com.oak.dms.importexport.dto.CreateExportRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Which documents an export covers - always only the tenant's own,
 * non-deleted documents. Chosen ids keep the order they were given in (and
 * ids from another tenant are silently dropped, never revealed); a filter
 * returns the type's documents oldest first. Documents in folders hidden
 * from the requester (the request's server-set hidden folders, V53) are
 * always left out. Used for the count shown
 * before an export starts and again by the worker, so a document deleted
 * in between is simply left out.
 */
@Component
@RequiredArgsConstructor
public class ExportDocumentSelector {

    private static final OffsetDateTime BEGINNING = OffsetDateTime.of(1970, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime END = OffsetDateTime.of(9999, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    /** Matches no folder - keeps a NOT IN list non-empty. */
    private static final UUID NO_FOLDER = new UUID(0, 0);

    private final DocumentRepository documentRepository;
    private final FolderRepository folderRepository;

    /** @param limit at most this many ids come back - callers pass their maximum + 1 to detect "too many" */
    @Transactional(readOnly = true)
    public List<UUID> select(UUID tenantId, CreateExportRequest request, int limit) {
        if (request.byIds()) {
            Set<UUID> wanted = new LinkedHashSet<>(request.documentIds());
            Map<UUID, Document> found = chosen(tenantId, request).stream()
                    .collect(Collectors.toMap(Document::getDocumentId, Function.identity()));
            return wanted.stream().filter(found::containsKey).limit(limit).toList();
        }
        if (request.byFolder()) {
            List<UUID> folders = folders(tenantId, request);
            return folders.isEmpty() ? List.of()
                    : documentRepository.findIdsInFolders(tenantId, folders, hiddenDocs(request),
                    PageRequest.of(0, limit));
        }
        return documentRepository.findIdsByTypeCreatedBetween(tenantId, request.documentTypeId(),
                from(request.createdFrom()), to(request.createdTo()), hidden(request), hiddenDocs(request),
                PageRequest.of(0, limit));
    }

    @Transactional(readOnly = true)
    public long count(UUID tenantId, CreateExportRequest request) {
        if (request.byIds()) {
            return chosen(tenantId, request).size();
        }
        if (request.byFolder()) {
            List<UUID> folders = folders(tenantId, request);
            return folders.isEmpty() ? 0 : documentRepository.countInFolders(tenantId, folders, hiddenDocs(request));
        }
        return documentRepository.countByTypeCreatedBetween(tenantId, request.documentTypeId(),
                from(request.createdFrom()), to(request.createdTo()), hidden(request), hiddenDocs(request));
    }

    /** The chosen documents that exist and aren't hidden from the requester. */
    private List<Document> chosen(UUID tenantId, CreateExportRequest request) {
        Set<UUID> hiddenFolders = request.hiddenFolders();
        Set<UUID> hiddenDocuments = request.hiddenDocuments();
        return documentRepository.findAllByDocumentIdInAndTenantIdAndDeletedFalse(
                        new LinkedHashSet<>(request.documentIds()), tenantId).stream()
                .filter(d -> !hiddenDocuments.contains(d.getDocumentId()))
                .filter(d -> d.getAccessMode() == FolderAccessMode.restricted
                        || d.getFolderId() == null || !hiddenFolders.contains(d.getFolderId()))
                .toList();
    }

    /** The folder (and its subfolders unless excluded), minus any hidden from the requester. */
    private List<UUID> folders(UUID tenantId, CreateExportRequest request) {
        Set<UUID> hidden = request.hiddenFolders();
        List<UUID> folders = request.subfoldersIncluded()
                ? folderRepository.findSubtreeIds(tenantId, request.folderId())
                : List.of(request.folderId());
        return folders.stream().filter(id -> !hidden.contains(id)).toList();
    }

    /** Hidden folders for a NOT IN clause, which must never be empty. */
    private static Set<UUID> hidden(CreateExportRequest request) {
        Set<UUID> hidden = request.hiddenFolders();
        return hidden.isEmpty() ? Set.of(NO_FOLDER) : hidden;
    }

    /** Hidden restricted documents for a NOT IN clause, which must never be empty. */
    private static Set<UUID> hiddenDocs(CreateExportRequest request) {
        Set<UUID> hidden = request.hiddenDocuments();
        return hidden.isEmpty() ? Set.of(NO_FOLDER) : hidden;
    }

    private static OffsetDateTime from(LocalDate date) {
        return date == null ? BEGINNING : date.atStartOfDay().atOffset(ZoneOffset.UTC);
    }

    /** Inclusive: documents created any time on {@code date} count. */
    private static OffsetDateTime to(LocalDate date) {
        return date == null ? END : date.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);
    }
}
