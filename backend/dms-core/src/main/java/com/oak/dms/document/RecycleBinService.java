package com.oak.dms.document;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.folder.DocumentAccessService;
import com.oak.dms.folder.DocumentAccessService.DocumentAccessView;
import com.oak.dms.folder.Folder;
import com.oak.dms.folder.FolderAccessLevel;
import com.oak.dms.folder.FolderException;
import com.oak.dms.relationship.service.RelationshipPurgeHandler;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.storage.entity.StorageLocation;
import com.oak.dms.storage.exception.StorageObjectNotFoundException;
import com.oak.dms.storage.repository.StorageLocationRepository;
import com.oak.dms.storage.service.ObjectStorageServiceFactory;
import com.oak.dms.user.User;
import com.oak.dms.user.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The recycle bin (module RBR). A deleted document stays here, restorable,
 * for its tenant's retention period (recycle_bin_settings, V62; the platform
 * default otherwise), then is purged for good by the hourly sweep - or
 * straight away, by someone allowed to empty it.
 * <p>
 * A purge destroys every version's file and its encryption key, and removes
 * the document's metadata values and access entries. The document and
 * version rows stay as tombstones ({@code purged_at}): scan results, import
 * rows and audit events that refer to them must stay intact. A document with
 * a protected version ({@code is_protected}) is never purged - protection is
 * a hold.
 * <p>
 * Access is the document's own, as when it was deleted: restoring or purging
 * needs edit, the same as deleting.
 */
@Slf4j
@Service
public class RecycleBinService {

    /** Arbitrary, fixed: "dms-recycle-bin-purge". */
    static final long LOCK_KEY = 0x646d_735f_7262_7067L;
    private static final int MAX_LISTED = 500;
    private static final int PURGE_BATCH = 200;

    private final DocumentRepository documentRepository;
    private final DocumentVersionRepository versionRepository;
    private final DocumentAccessService documentAccessService;
    private final StorageLocationRepository storageLocationRepository;
    private final ObjectStorageServiceFactory storage;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final JdbcTemplate jdbc;
    private final OcrArtifactWriter ocrArtifactWriter;
    private final PageImageCache pageImageCache;
    private final TransactionTemplate transactions;
    private final RelationshipPurgeHandler relationshipPurge;
    private final int defaultRetentionDays;

    public RecycleBinService(DocumentRepository documentRepository, DocumentVersionRepository versionRepository,
                             DocumentAccessService documentAccessService,
                             StorageLocationRepository storageLocationRepository, ObjectStorageServiceFactory storage,
                             UserRepository userRepository, AuditService auditService, JdbcTemplate jdbc,
                             OcrArtifactWriter ocrArtifactWriter, PageImageCache pageImageCache,
                             PlatformTransactionManager transactionManager, RelationshipPurgeHandler relationshipPurge,
                             @Value("${dms.documents.recycle-bin.retention-days:30}") int defaultRetentionDays) {
        this.documentRepository = documentRepository;
        this.versionRepository = versionRepository;
        this.documentAccessService = documentAccessService;
        this.storageLocationRepository = storageLocationRepository;
        this.storage = storage;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.jdbc = jdbc;
        this.ocrArtifactWriter = ocrArtifactWriter;
        this.pageImageCache = pageImageCache;
        this.transactions = new TransactionTemplate(transactionManager);
        this.relationshipPurge = relationshipPurge;
        this.defaultRetentionDays = defaultRetentionDays;
    }

    /**
     * @param folderName   null for one not in a folder, or whose folder has since been deleted
     * @param folderGone   its folder was deleted - it can't be restored back into it
     * @param purgeAt      when the sweep deletes it for good
     * @param protectedVersion a version is protected - it won't be purged
     */
    public record RecycleBinItem(UUID documentId, String title, String filename, long sizeBytes, UUID folderId,
                                 String folderName, boolean folderGone, OffsetDateTime deletedAt, UUID deletedBy,
                                 String deletedByName, OffsetDateTime purgeAt, boolean protectedVersion) {
    }

    /** @param retentionDays its own period; null = the organisation's */
    public record TypeRetention(UUID documentTypeId, String typeName, Integer retentionDays) {
    }

    /** @param byType the active document types, each with its own period if it has one */
    public record RecycleBin(int retentionDays, List<RecycleBinItem> items, List<TypeRetention> byType) {
    }

    /** The caller's tenant's deleted documents they can still open, most recently deleted first. */
    @Transactional(readOnly = true)
    public RecycleBin list() {
        UUID tenantId = TenantContext.getTenantId();
        int retention = retentionDays(tenantId);
        DocumentAccessView access = documentAccessService.forCaller();
        List<Document> deleted = documentRepository.findRecycleBin(tenantId, PageRequest.of(0, MAX_LISTED)).stream()
                .filter(d -> access.admin() || canOpen(access, d))
                .toList();
        Set<UUID> people = deleted.stream().map(Document::getUpdatedBy).filter(Objects::nonNull)
                .collect(Collectors.toCollection(HashSet::new));
        Map<UUID, String> names = userRepository.findAllById(people).stream()
                .filter(u -> tenantId.equals(u.getTenantId()))
                .collect(Collectors.toMap(User::getUserId,
                        u -> u.getDisplayName() != null ? u.getDisplayName() : u.getEmail(), (a, b) -> a));
        Set<UUID> protectedDocs = Set.copyOf(jdbc.queryForList("select distinct document_id from document_versions "
                + "where tenant_id = ? and is_protected and purged_at is null", UUID.class, tenantId));
        List<TypeRetention> byType = typeRetentions(tenantId);
        Map<UUID, Integer> typeDays = new java.util.HashMap<>();
        byType.stream().filter(t -> t.retentionDays() != null)
                .forEach(t -> typeDays.put(t.documentTypeId(), t.retentionDays()));
        List<RecycleBinItem> items = deleted.stream().map(d -> {
            DocumentVersion current = currentVersion(d);
            Folder folder = d.getFolderId() == null ? null : access.folders().folder(d.getFolderId()).orElse(null);
            OffsetDateTime deletedAt = deletedAt(d);
            return new RecycleBinItem(d.getDocumentId(), d.getTitle(),
                    current != null ? current.getOriginalFilename() : null,
                    current != null ? current.getSizeBytes() : 0, d.getFolderId(),
                    folder != null ? folder.getFolderName() : null, d.getFolderId() != null && folder == null,
                    deletedAt, d.getUpdatedBy(), names.get(d.getUpdatedBy()),
                    deletedAt.plusDays(typeDays.getOrDefault(d.getDocumentTypeId(), retention)),
                    protectedDocs.contains(d.getDocumentId()));
        }).toList();
        return new RecycleBin(retention, items, byType);
    }

    /** Back where it was. Refused when its folder has since been deleted. Audited as DOCUMENT_RESTORED. */
    @Transactional
    public void restore(UUID documentId) {
        Document document = inBin(documentId);
        DocumentAccessView access = documentAccessService.forCaller();
        documentAccessService.require(access, document, FolderAccessLevel.edit);
        if (document.getFolderId() != null && access.folders().folder(document.getFolderId()).isEmpty()) {
            throw new FolderException(HttpStatus.CONFLICT, "DOCUMENT_FOLDER_GONE",
                    "\"" + document.getTitle() + "\" was in a folder that has since been deleted, so it can't go "
                            + "back there. Ask an administrator to restore it somewhere else.");
        }
        document.setDeleted(false);
        document.setDeletedAt(null);
        document.setUpdatedBy(TenantContext.getUserId());
        document.setUpdatedAt(OffsetDateTime.now());
        documentRepository.save(document);
        auditService.event(AuditActionCatalog.DOCUMENT_RESTORED)
                .objectType(AuditObjectTypes.DOCUMENT)
                .objectId(documentId)
                .resourceName(document.getTitle())
                .outcome(AuditOutcome.SUCCESS)
                .log();
    }

    /**
     * Deletes it for good now, instead of at the end of the retention period.
     * <p>
     * FOLLOW-UP: a document whose folder was deleted after it (deleting the
     * documents empties the folder, so it can then be deleted) is left out of
     * {@link #list} and answers 404 here, because the access check can't find
     * the folder. It is still purged by the sweep at the end of retention, but
     * nobody can see it or purge it sooner, and the "ask an administrator to
     * restore it" in {@link #restore} has no admin screen behind it. Either
     * check access against the nearest surviving ancestor (or the top level),
     * or let a folder delete send its deleted documents along with it.
     */
    public void purgeNow(UUID documentId) {
        Document document = inBin(documentId);
        documentAccessService.require(documentAccessService.forCaller(), document, FolderAccessLevel.edit);
        if (hasProtectedVersion(document)) {
            throw new FolderException(HttpStatus.CONFLICT, "DOCUMENT_VERSION_PROTECTED",
                    "\"" + document.getTitle() + "\" has a protected version, so it can't be deleted permanently.");
        }
        purge(document, "emptied from the recycle bin");
    }

    @Transactional(readOnly = true)
    public int retentionDays() {
        return retentionDays(TenantContext.getTenantId());
    }

    /** How long the caller's tenant keeps deleted documents. Audited as RECYCLE_BIN_RETENTION_CHANGED. */
    @Transactional
    public int setRetentionDays(int days) {
        if (days < 1 || days > 3650) {
            throw new FolderException(HttpStatus.BAD_REQUEST, "RECYCLE_BIN_RETENTION_INVALID",
                    "Keep deleted documents for between 1 and 3650 days.");
        }
        UUID tenantId = TenantContext.getTenantId();
        int before = retentionDays(tenantId);
        jdbc.update("""
                insert into recycle_bin_settings (tenant_id, retention_days, updated_at, updated_by)
                values (?, ?, now(), ?)
                on conflict (tenant_id) do update set retention_days = excluded.retention_days,
                    updated_at = now(), updated_by = excluded.updated_by
                """, tenantId, days, TenantContext.getUserId());
        auditService.event(AuditActionCatalog.RECYCLE_BIN_RETENTION_CHANGED)
                .objectType(AuditObjectTypes.DOCUMENT)
                .resourceName("Recycle bin")
                .outcome(AuditOutcome.SUCCESS)
                .previousValue(Map.of("retentionDays", before))
                .newValue(Map.of("retentionDays", days))
                .log();
        return days;
    }

    private List<TypeRetention> typeRetentions(UUID tenantId) {
        return jdbc.query("""
                select document_type_id, type_name, recycle_bin_retention_days from document_types
                where tenant_id = ? and not is_deleted order by lower(type_name)
                """, (rs, i) -> new TypeRetention((UUID) rs.getObject(1), rs.getString(2),
                (Integer) rs.getObject(3)), tenantId);
    }

    /**
     * How long deleted documents of one type are kept - overriding the organisation's period, or
     * {@code null} to follow it again. Audited as RECYCLE_BIN_RETENTION_CHANGED.
     */
    @Transactional
    public void setTypeRetentionDays(UUID documentTypeId, Integer days) {
        if (days != null && (days < 1 || days > 3650)) {
            throw new FolderException(HttpStatus.BAD_REQUEST, "RECYCLE_BIN_RETENTION_INVALID",
                    "Keep deleted documents for between 1 and 3650 days.");
        }
        UUID tenantId = TenantContext.getTenantId();
        List<Map<String, Object>> type = jdbc.queryForList("select type_name, recycle_bin_retention_days "
                + "from document_types where document_type_id = ? and tenant_id = ? and not is_deleted",
                documentTypeId, tenantId);
        if (type.isEmpty()) {
            throw new ResourceNotFoundException("DocumentType", documentTypeId);
        }
        jdbc.update("update document_types set recycle_bin_retention_days = ? where document_type_id = ?",
                days, documentTypeId);
        Map<String, Object> before = new java.util.HashMap<>();
        before.put("retentionDays", type.get(0).get("recycle_bin_retention_days"));
        Map<String, Object> after = new java.util.HashMap<>();
        after.put("retentionDays", days);
        auditService.event(AuditActionCatalog.RECYCLE_BIN_RETENTION_CHANGED)
                .objectType(AuditObjectTypes.DOCUMENT)
                .resourceName("Recycle bin - " + type.get(0).get("type_name"))
                .outcome(AuditOutcome.SUCCESS)
                .previousValue(before)
                .newValue(after)
                .log();
    }

    /**
     * The sweep: documents deleted longer ago than their tenant keeps them are purged. One instance at
     * a time (a Postgres session lock); a failure is retried on the next run.
     */
    @Scheduled(fixedDelayString = "${dms.documents.recycle-bin.purge-interval:PT1H}", initialDelayString = "PT10M")
    public void purgeExpired() {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            if (!lock(connection, "select pg_try_advisory_lock(?)")) {
                return null;
            }
            try {
                for (Map<String, Object> row : jdbc.queryForList("""
                        select d.document_id from documents d
                        left join recycle_bin_settings s on s.tenant_id = d.tenant_id
                        left join document_types t on t.document_type_id = d.document_type_id
                        where d.is_deleted and d.purged_at is null
                          and coalesce(d.deleted_at, d.updated_at, d.created_at)
                              < now() - make_interval(days => coalesce(t.recycle_bin_retention_days,
                                                                       s.retention_days, ?))
                          and not exists (select 1 from document_versions v where v.document_id = d.document_id
                                          and v.is_protected and v.purged_at is null)
                        order by d.deleted_at limit ?
                        """, defaultRetentionDays, PURGE_BATCH)) {
                    UUID documentId = (UUID) row.get("document_id");
                    try {
                        documentRepository.findById(documentId).ifPresent(d -> purge(d, "retention period ended"));
                    } catch (RuntimeException e) {
                        log.warn("Could not purge deleted document {} - will try again: {}", documentId,
                                e.getMessage());
                    }
                }
            } finally {
                lock(connection, "select pg_advisory_unlock(?)");
            }
            return null;
        });
    }

    /**
     * Destroys the files first - deleting one that's already gone is fine, so a purge interrupted part
     * way simply runs again - then, in one transaction, marks the rows and removes metadata and access.
     */
    void purge(Document document, String why) {
        List<DocumentVersion> versions = versionRepository.findByDocumentIdAndTenantId(document.getDocumentId(),
                document.getTenantId());
        int files = 0;
        for (DocumentVersion version : versions) {
            if (version.getPurgedAt() != null) {
                continue;
            }
            StorageLocation location = storageLocationRepository.findById(version.getStorageLocationId())
                    .orElse(null);
            if (location == null) {
                continue; // its location is gone - so is the file
            }
            try {
                storage.resolve(location).delete(location, version.getStorageObjectKey());
            } catch (StorageObjectNotFoundException e) {
                // already gone
            }
            if (version.getRenditionObjectKey() != null) {
                // Its preview PDF (V64) holds the same content - it goes too.
                try {
                    storage.resolve(location).delete(location, version.getRenditionObjectKey());
                } catch (StorageObjectNotFoundException e) {
                    // already gone
                }
            }
            // And everything OCR derived from it. OCR output IS the document's
            // text and the searchable PDF IS the document, so a purge that
            // left them behind would leave the document behind under another
            // name. Each key is deleted explicitly - see OcrArtifactWriter.
            //
            // Deliberately NOT swallowed. A failure here must stop the purge
            // before `purged_at` is set, because once it is set this document
            // is out of the sweep's query for good - a swallowed failure would
            // strand the document's text in storage permanently, and the log
            // line saying so would scroll away. Letting it throw leaves the
            // document in the bin, and `purgeExpired` retries it next hour
            // ("Could not purge deleted document ... - will try again").
            // Re-running is safe: deleting an object that is already gone is a
            // no-op, which is the same property the file deletion above
            // relies on.
            ocrArtifactWriter.deleteFor(version);
            // And any page image of it still held in memory. A page image is
            // the document as a picture; a purged document must not stay
            // readable in a cache for the rest of the process's life.
            pageImageCache.invalidate(version.getVersionId());
            files++;
        }
        transactions.executeWithoutResult(status -> {
            jdbc.update("update document_versions set purged_at = now(), is_deleted = true, "
                    + "deleted_at = coalesce(deleted_at, now()) where document_id = ? and purged_at is null",
                    document.getDocumentId());
            jdbc.update("delete from document_metadata where document_id = ?", document.getDocumentId());
            jdbc.update("delete from document_access where document_id = ?", document.getDocumentId());
            // Links to or from it are removed (the history and an audit record per link stay) - 2.8 DRM-FR-011.
            relationshipPurge.removeLinksOf(document.getTenantId(), document.getDocumentId(), document.getTitle());
            jdbc.update("update documents set purged_at = now() where document_id = ?", document.getDocumentId());
        });
        auditService.event(AuditActionCatalog.DOCUMENT_PURGED)
                .tenantId(document.getTenantId())
                .objectType(AuditObjectTypes.DOCUMENT)
                .objectId(document.getDocumentId())
                .resourceName(document.getTitle())
                .outcome(AuditOutcome.SUCCESS)
                .newValue(Map.of("why", why, "versions", versions.size(), "filesDestroyed", files))
                .log();
        log.info("Purged document {} ({}): {} file(s) destroyed", document.getDocumentId(), why, files);
    }

    private Document inBin(UUID documentId) {
        return documentRepository.findInRecycleBin(documentId, TenantContext.getTenantId())
                .orElseThrow(() -> new ResourceNotFoundException("Document", documentId));
    }

    private boolean canOpen(DocumentAccessView access, Document document) {
        return access.canSee(document);
    }

    private boolean hasProtectedVersion(Document document) {
        Boolean any = jdbc.queryForObject("select exists (select 1 from document_versions where document_id = ? "
                + "and is_protected and purged_at is null)", Boolean.class, document.getDocumentId());
        return Boolean.TRUE.equals(any);
    }

    private DocumentVersion currentVersion(Document document) {
        UUID id = document.getCurrentVersionId() != null ? document.getCurrentVersionId()
                : document.getLatestVersionId();
        return id == null ? null : versionRepository.findById(id).orElse(null);
    }

    private static OffsetDateTime deletedAt(Document document) {
        return document.getDeletedAt() != null ? document.getDeletedAt()
                : document.getUpdatedAt() != null ? document.getUpdatedAt() : document.getCreatedAt();
    }

    private int retentionDays(UUID tenantId) {
        List<Integer> rows = jdbc.queryForList("select retention_days from recycle_bin_settings where tenant_id = ?",
                Integer.class, tenantId);
        return rows.isEmpty() ? defaultRetentionDays : rows.get(0);
    }

    private static boolean lock(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, LOCK_KEY);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }
}
