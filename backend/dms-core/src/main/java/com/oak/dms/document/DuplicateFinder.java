package com.oak.dms.document;

import com.oak.dms.folder.DocumentAccessService;
import com.oak.dms.folder.DocumentAccessService.DocumentAccessView;
import com.oak.dms.folder.Folder;
import com.oak.dms.folder.FolderAccessLevel;
import com.oak.dms.security.auth.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Finds the documents an upload would duplicate: the same content (its
 * SHA-256 equals a stored version's file_hash) anywhere the uploader can see,
 * or a file with the same name in the folder it is going into.
 * <p>
 * Only documents the caller can open are ever reported (V53/V54) - a check
 * must never reveal that a hidden document exists. Used by the upload screen
 * before it uploads (the browser sends the hashes) and by bulk import as it
 * creates records.
 */
@Service
@RequiredArgsConstructor
public class DuplicateFinder {

    private static final int LIMIT = 20;

    private final JdbcTemplate jdbc;
    private final DocumentRepository documentRepository;
    private final DocumentAccessService documentAccessService;

    /**
     * One document the upload would duplicate.
     *
     * @param sameContent    the file's content equals one of its versions
     * @param identicalToCurrent the file is byte-for-byte its current version - a new version would change nothing
     * @param sameName       its current file has the same name, in the same folder
     * @param matchedVersion the version label whose content matches ("1.2"), when {@code sameContent}
     * @param canAddVersion  the caller may add a version to it (edit access)
     * @param similarity     for a document with nearly the same text (not the same bytes): how alike, 0-100
     */
    public record Match(UUID documentId, String title, String fileName, UUID folderId, String folderName,
                        UUID documentTypeId, boolean sameContent, boolean identicalToCurrent, boolean sameName,
                        String matchedVersion, boolean canAddVersion, Integer similarity) {

        public Match(UUID documentId, String title, String fileName, UUID folderId, String folderName,
                     UUID documentTypeId, boolean sameContent, boolean identicalToCurrent, boolean sameName,
                     String matchedVersion, boolean canAddVersion) {
            this(documentId, title, fileName, folderId, folderName, documentTypeId, sameContent, identicalToCurrent,
                    sameName, matchedVersion, canAddVersion, null);
        }
    }

    /**
     * Documents whose current text is nearly the same as a file's (its SimHash within
     * {@link DocumentFingerprints#SIMILAR_WITHIN_BITS} bits) - a re-saved copy, a light edit, the same
     * report under another name. Only documents the caller can open; most alike first.
     */
    @Transactional(readOnly = true)
    public List<Match> findSimilar(long simhash) {
        UUID tenantId = TenantContext.getTenantId();
        DocumentAccessView access = documentAccessService.forCaller();
        Map<UUID, long[]> hits = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbc.queryForList("""
                select d.document_id, v.content_simhash,
                       coalesce(v.version_label, v.major_version || '.' || v.minor_version) as label
                from document_versions v
                join documents d on d.document_id = v.document_id
                     and v.version_id = coalesce(d.current_version_id, d.latest_version_id)
                where v.tenant_id = ? and v.content_simhash is not null and not v.is_deleted
                  and not d.is_deleted and d.purged_at is null and not d.is_template
                  and bit_count((v.content_simhash # ?)::bit(64)) <= ?
                limit 200
                """, tenantId, simhash, DocumentFingerprints.SIMILAR_WITHIN_BITS)) {
            hits.put((UUID) row.get("document_id"), new long[]{((Number) row.get("content_simhash")).longValue()});
        }
        if (hits.isEmpty()) {
            return List.of();
        }
        List<Match> matches = new ArrayList<>();
        for (Document document : documentRepository.findAllByDocumentIdInAndTenantIdAndDeletedFalse(hits.keySet(),
                tenantId)) {
            if (!access.canSee(document)) {
                continue;
            }
            String currentFile = jdbc.queryForList("select original_filename from document_versions where version_id = ?",
                    String.class, document.getCurrentVersionId() != null ? document.getCurrentVersionId()
                            : document.getLatestVersionId()).stream().findFirst().orElse(null);
            Folder folder = document.getFolderId() == null ? null
                    : access.folders().folder(document.getFolderId()).orElse(null);
            matches.add(new Match(document.getDocumentId(), document.getTitle(), currentFile, document.getFolderId(),
                    folder != null ? folder.getFolderName() : null, document.getDocumentTypeId(), false, false,
                    false, null, access.level(document).atLeast(FolderAccessLevel.edit),
                    DocumentFingerprints.similarity(simhash, hits.get(document.getDocumentId())[0])));
        }
        matches.sort((a, b) -> Integer.compare(b.similarity(), a.similarity()));
        return matches.size() > LIMIT ? matches.subList(0, LIMIT) : matches;
    }

    /** @param sha256 hex, lower case; null to check the name only */
    @Transactional(readOnly = true)
    public List<Match> find(String sha256, String fileName, UUID folderId) {
        return find(documentAccessService.forCaller(), sha256, fileName, folderId);
    }

    @Transactional(readOnly = true)
    public List<Match> find(DocumentAccessView access, String sha256, String fileName, UUID folderId) {
        UUID tenantId = TenantContext.getTenantId();
        Map<UUID, Hit> hits = new LinkedHashMap<>();
        if (sha256 != null && !sha256.isBlank()) {
            for (Map<String, Object> row : jdbc.queryForList("""
                    select d.document_id, v.version_id, coalesce(v.version_label,
                           v.major_version || '.' || v.minor_version) as label,
                           v.version_id = coalesce(d.current_version_id, d.latest_version_id) as is_current
                    from document_versions v
                    join documents d on d.document_id = v.document_id
                    where v.tenant_id = ? and v.file_hash = ? and not v.is_deleted
                      and not d.is_deleted and d.purged_at is null and not d.is_template
                    order by is_current desc, v.version_number desc
                    limit 200
                    """, tenantId, sha256.toLowerCase(Locale.ROOT))) {
                Hit hit = hits.computeIfAbsent((UUID) row.get("document_id"), id -> new Hit());
                if (hit.matchedVersion == null) {
                    hit.matchedVersion = (String) row.get("label");
                }
                hit.sameContent = true;
                hit.identicalToCurrent |= Boolean.TRUE.equals(row.get("is_current"));
            }
        }
        if (fileName != null && !fileName.isBlank()) {
            for (UUID documentId : jdbc.queryForList("""
                    select d.document_id from documents d
                    join document_versions cv on cv.version_id = coalesce(d.current_version_id, d.latest_version_id)
                    where d.tenant_id = ? and not d.is_deleted and d.purged_at is null and not d.is_template
                      and d.folder_id is not distinct from ?
                      and lower(cv.original_filename) = lower(?)
                    limit 200
                    """, UUID.class, tenantId, folderId, fileName.trim())) {
                hits.computeIfAbsent(documentId, id -> new Hit()).sameName = true;
            }
        }
        if (hits.isEmpty()) {
            return List.of();
        }

        List<Match> matches = new ArrayList<>();
        for (Document document : documentRepository.findAllByDocumentIdInAndTenantIdAndDeletedFalse(hits.keySet(),
                tenantId)) {
            if (!access.canSee(document)) {
                continue; // never say a hidden document exists
            }
            Hit hit = hits.get(document.getDocumentId());
            String currentFile = jdbc.queryForList("select original_filename from document_versions where version_id = ?",
                    String.class, document.getCurrentVersionId() != null ? document.getCurrentVersionId()
                            : document.getLatestVersionId()).stream().findFirst().orElse(null);
            Folder folder = document.getFolderId() == null ? null
                    : access.folders().folder(document.getFolderId()).orElse(null);
            matches.add(new Match(document.getDocumentId(), document.getTitle(), currentFile, document.getFolderId(),
                    folder != null ? folder.getFolderName() : null, document.getDocumentTypeId(), hit.sameContent,
                    hit.identicalToCurrent, hit.sameName, hit.matchedVersion,
                    access.level(document).atLeast(FolderAccessLevel.edit)));
        }
        // The most telling first: same name in this folder, then identical content.
        matches.sort((a, b) -> Integer.compare(rank(b), rank(a)));
        return matches.size() > LIMIT ? matches.subList(0, LIMIT) : matches;
    }

    private static int rank(Match match) {
        return (match.sameName() ? 2 : 0) + (match.sameContent() ? 1 : 0);
    }

    private static final class Hit {
        boolean sameContent;
        boolean identicalToCurrent;
        boolean sameName;
        String matchedVersion;
    }
}
