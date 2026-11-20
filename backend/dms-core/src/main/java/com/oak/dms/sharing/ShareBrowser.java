package com.oak.dms.sharing;

import com.oak.dms.common.exception.ApiException;
import com.oak.dms.document.Document;
import com.oak.dms.document.DocumentContentReader;
import com.oak.dms.document.DocumentRepository;
import com.oak.dms.document.SharedContentReader;
import com.oak.dms.document.dto.DownloadedDocument;
import com.oak.dms.folder.Folder;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.sharing.ShareTargets.FolderTree;
import com.oak.dms.sharing.SharingDtos.Crumb;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaTypeFactory;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What the recipient of a share link or a portal guest sees and gets: the entries of a shared folder, and the
 * bytes of a shared document. It applies the item rules ({@link ShareTargets}) on every call and trusts nothing
 * about the caller - who may be here at all is {@link PublicShareService} / {@link PortalService}'s job.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class ShareBrowser {

    /** Stands in for "nothing hidden" so an IN clause is never empty. */
    private static final UUID NONE = new UUID(0, 0);
    private static final int MAX_ENTRIES = 500;

    private final ShareTargets targets;
    private final DocumentRepository documentRepository;
    private final SharedContentReader content;

    /** One thing in a folder listing; {@code folder} entries carry no size or type. */
    record Entry(String type, UUID id, String name, String mimeType, Long sizeBytes, OffsetDateTime updatedAt) {
    }

    /** A file ready to stream: {@code inline} = show it in the browser, else send it as a download. */
    record Served(DownloadedDocument file, boolean inline) {
    }

    /** The sub-folders and documents of {@code folderId} that may be shown, folders first, each A-Z. */
    List<Entry> children(UUID tenantId, FolderTree tree, UUID folderId, UUID rootFolderId) {
        List<Entry> entries = new ArrayList<>();
        tree.childrenOf(folderId).stream()
                .filter(f -> targets.servableBelow(f, tree, rootFolderId))
                .sorted(Comparator.comparing(Folder::getFolderName, String.CASE_INSENSITIVE_ORDER))
                .forEach(f -> entries.add(new Entry(ShareLink.TYPE_FOLDER, f.getFolderId(), f.getFolderName(), null,
                        null, f.getUpdatedAt())));
        List<UUID> ids = documentRepository.findIdsInFolders(tenantId, List.of(folderId), List.of(NONE),
                PageRequest.of(0, MAX_ENTRIES));
        if (!ids.isEmpty()) {
            documentRepository.findAllByDocumentIdInAndTenantIdAndDeletedFalse(ids, tenantId).stream()
                    .filter(d -> targets.servableBelow(d, tree, rootFolderId))
                    .sorted(Comparator.comparing(Document::getTitle, String.CASE_INSENSITIVE_ORDER))
                    .forEach(d -> documentEntry(tenantId, d).ifPresent(entries::add));
        }
        return entries;
    }

    /** A document as a listing entry; empty when it has no readable current version. */
    Optional<Entry> documentEntry(UUID tenantId, Document document) {
        try {
            DocumentContentReader.EditableVersion version = content.describe(tenantId, document.getDocumentId());
            String mime = MediaTypeFactory.getMediaType(version.filename()).map(Object::toString).orElse(null);
            return Optional.of(new Entry(ShareLink.TYPE_DOCUMENT, document.getDocumentId(), document.getTitle(), mime,
                    version.sizeBytes(), version.createdAt()));
        } catch (ApiException e) {
            return Optional.empty();
        }
    }

    /** The breadcrumb from the shared root down to {@code folderId}, both included. */
    List<Crumb> breadcrumb(FolderTree tree, UUID rootFolderId, UUID folderId) {
        return tree.pathFrom(rootFolderId, folderId).stream()
                .map(f -> new Crumb(f.getFolderId(), f.getFolderName())).toList();
    }

    /**
     * The document's bytes. A download is the original file. Otherwise it is what a browser can show: the PDF
     * rendition of an Office file, or a PDF or picture as it is - and 415 for anything else, so the page can say so
     * and offer the download (if the link allows it) instead of a blank frame.
     */
    Served serve(UUID tenantId, UUID actingUserId, UUID documentId, boolean download) {
        // The storage layer (keys, decryption) reads the tenant from TenantContext, and a recipient has no login to
        // set it - so it is bound here, from the verified link or grant, for the length of the read only (as the
        // WOPI endpoints do). The returned stream is already open, so it needs no context to be copied out.
        TenantContext.set(tenantId, actingUserId);
        try {
            if (download) {
                return new Served(content.original(tenantId, documentId), false);
            }
            return content.viewable(tenantId, documentId).map(file -> new Served(file, true))
                    .orElseThrow(() -> new SharingException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "SHARE_NO_PREVIEW",
                            "This kind of file can't be shown in the browser."));
        } finally {
            TenantContext.clear();
        }
    }
}
