package com.oak.dms.document;

import com.oak.dms.document.dto.DownloadedDocument;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * What the sharing module (1.5) may read of a document: its current version, for someone with no login.
 * <p>
 * Like {@link DocumentContentReader#openCurrentForEditing} it does NOT check a caller's folder access - there is
 * no caller. The sharing module has already established that a live link or grant covers this document, and it
 * passes the tenant from that link, never from the request. What is still enforced here is the storage side
 * (the tenant's location, the scan verdict, not purged) through {@code VersionContent}.
 */
@Component
@RequiredArgsConstructor
public class SharedContentReader {

    /** Shown in the browser as they are. SVG is left out on purpose: opened directly it can run script. */
    private static final Set<String> INLINE_AS_IS = Set.of("pdf", "png", "jpg", "jpeg", "gif", "webp", "bmp");

    private final DocumentContentReader contentReader;
    private final DocumentRenditionService renditionService;

    /** Name, size and type of the current version - for listings and headers. */
    @Transactional(readOnly = true)
    public DocumentContentReader.EditableVersion describe(UUID tenantId, UUID documentId) {
        return contentReader.currentForEditing(tenantId, documentId);
    }

    /**
     * What a browser can show for view-only access: the PDF rendition of an Office file, or the file itself when it
     * is a PDF or a picture. Empty for anything else (a zip, a CSV...) - the caller says there is no preview.
     */
    @Transactional
    public Optional<DownloadedDocument> viewable(UUID tenantId, UUID documentId) {
        String filename = contentReader.currentForEditing(tenantId, documentId).filename();
        if (INLINE_AS_IS.contains(extension(filename))) {
            return Optional.of(contentReader.openCurrentForEditing(tenantId, documentId));
        }
        return renditionService.previewPdfForShare(tenantId, documentId);
    }

    /** The original bytes, for a link or grant that allows download. */
    @Transactional
    public DownloadedDocument original(UUID tenantId, UUID documentId) {
        return contentReader.openCurrentForEditing(tenantId, documentId);
    }

    private static String extension(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
