package com.oak.dms.compliance;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.document.DocumentVersion;
import com.oak.dms.document.DocumentVersionRepository;
import com.oak.dms.document.RecycleBinService;
import com.oak.dms.document.Document;
import com.oak.dms.security.auth.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Retention for OLD VERSIONS, driven by the same rules as the documents themselves (6.1 follow-up): a rule can keep at most
 * N versions and/or remove superseded versions older than a period ({@link VersionTrimmer} decides which).
 *
 * <p>Removal is in two steps, like the recycle bin, so nothing irreversible happens at once:
 * <ol>
 *   <li><b>Remove</b> - the version is hidden from the version history ({@code is_deleted}, {@code retention_removed_at}).
 *       Its file is untouched, and an administrator can restore it.</li>
 *   <li><b>Destroy</b> - once the organisation's grace period has passed, its files, preview and OCR text are destroyed for
 *       good ({@code purged_at}), exactly as the recycle bin does ({@link RecycleBinService#destroyFiles}).</li>
 * </ol>
 * Restoring a version also exempts it from further trimming ({@code retention_keep}) - otherwise the next run would remove it
 * again. A document under legal hold is never touched by either step.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class VersionRetentionService {

    private static final int DESTROY_BATCH = 200;

    /** A version removed by retention and waiting out its grace period. */
    public record RemovedVersion(UUID versionId, UUID documentId, String documentTitle, int versionNumber, String fileName,
                                 long sizeBytes, OffsetDateTime removedAt, OffsetDateTime destroysAt) {
    }

    private final JdbcTemplate jdbc;
    private final DocumentVersionRepository versionRepository;
    private final RecycleBinService recycleBin;
    private final RetentionSettingsService settings;
    private final AuditService auditService;

    /**
     * Applies the rule's version limits to one document. Returns how many versions were removed (hidden).
     * A version in use, protected, kept by an administrator, still being scanned or signed is never removed.
     */
    public int removeOldVersions(Document document, RetentionPolicy policy, OffsetDateTime now) {
        if (!policy.limitsVersions() || document.isLegalHold()) {
            return 0;
        }
        Map<UUID, Integer> numbers = new LinkedHashMap<>();
        List<VersionTrimmer.Candidate> live = jdbc.query("""
                select v.version_id, v.version_number, v.created_at,
                       (v.is_protected or v.retention_keep or v.scan_status <> 'clean' or v.created_reason = 'signed_copy'
                        or v.version_id in (d.current_version_id, d.latest_version_id, d.published_version_id,
                                            d.signature_locked_version_id, d.standalone_signature_base_version_id)
                        or exists (select 1 from document_signatures s where s.version_id = v.version_id)
                        or exists (select 1 from workflow_instances i where i.document_version_id = v.version_id)
                        or exists (select 1 from workflow_supporting_documents w where w.document_version_id = v.version_id)
                        or exists (select 1 from workflow_tasks t where t.signature_placement_base_version_id = v.version_id)
                        or exists (select 1 from document_relationships r where r.removed_at is null
                                   and (r.source_version_id = v.version_id or r.target_version_id = v.version_id))
                       ) as untouchable
                from document_versions v join documents d on d.document_id = v.document_id
                where v.document_id = ? and v.tenant_id = ? and not v.is_deleted and v.purged_at is null
                order by v.version_number desc
                """, (rs, i) -> {
                    UUID id = rs.getObject("version_id", UUID.class);
                    numbers.put(id, rs.getInt("version_number"));
                    return new VersionTrimmer.Candidate(id, rs.getInt("version_number"),
                            rs.getObject("created_at", OffsetDateTime.class), rs.getBoolean("untouchable"));
                }, document.getDocumentId(), document.getTenantId());

        List<UUID> wanted = VersionTrimmer.toRemove(live, policy.getMaxVersions(), policy.versionAgeSpan(), now);
        List<Integer> removedNumbers = new ArrayList<>();
        for (UUID versionId : wanted) {
            // The WHERE re-checks what matters at the moment of the write: a hold placed meanwhile, protection added meanwhile.
            int changed = jdbc.update("""
                    update document_versions v set is_deleted = true, deleted_at = ?, retention_removed_at = ?
                    where v.version_id = ? and not v.is_deleted and v.purged_at is null and not v.is_protected
                      and not v.retention_keep
                      and exists (select 1 from documents d where d.document_id = v.document_id and not d.legal_hold)
                    """, now, now, versionId);
            if (changed == 1) {
                removedNumbers.add(numbers.get(versionId));
            }
        }
        if (!removedNumbers.isEmpty()) {
            Map<String, Object> change = new LinkedHashMap<>();
            change.put("versions", removedNumbers);
            change.put("rule", policy.getName());
            change.put("maxVersions", policy.getMaxVersions());
            change.put("olderThan", policy.versionAgeSpan() == null ? null : policy.getVersionAgeAmount() + " " + policy.getVersionAgeUnit());
            change.put("restorableUntil", now.plusDays(settings.forTenant(document.getTenantId()).versionGraceDays()).toString());
            auditService.event(AuditActionCatalog.DOCUMENT_VERSIONS_REMOVED).tenantId(document.getTenantId()).systemActor()
                    .objectType(AuditObjectTypes.DOCUMENT).objectId(document.getDocumentId())
                    .resourceName(document.getTitle()).outcome(AuditOutcome.SUCCESS).newValue(change).log();
        }
        return removedNumbers.size();
    }

    /**
     * Destroys the files of versions whose grace period has ended - for good. A version that fails (storage unreachable, say)
     * stays hidden and is tried again next run. Never touches a protected version or one whose document is under legal hold.
     *
     * @return how many versions were destroyed
     */
    public int destroyDue(UUID tenantId, OffsetDateTime now) {
        int graceDays = settings.forTenant(tenantId).versionGraceDays();
        OffsetDateTime cutoff = now.minusDays(graceDays);
        List<UUID> due = jdbc.queryForList("""
                select v.version_id from document_versions v join documents d on d.document_id = v.document_id
                where v.tenant_id = ? and v.retention_removed_at is not null and v.purged_at is null
                  and not v.is_protected and not d.legal_hold and v.retention_removed_at <= ?
                order by v.retention_removed_at limit ?
                """, UUID.class, tenantId, cutoff, DESTROY_BATCH);
        Map<UUID, List<Integer>> perDocument = new LinkedHashMap<>();
        Map<UUID, String> titles = new LinkedHashMap<>();
        int destroyed = 0;
        for (UUID versionId : due) {
            try {
                DocumentVersion version = versionRepository.findById(versionId).orElse(null);
                if (version == null) {
                    continue;
                }
                recycleBin.destroyFiles(version);
                // Only now is it purged: a failure above leaves it hidden and retried, never half-destroyed and forgotten.
                if (jdbc.update("update document_versions set purged_at = now() where version_id = ? and purged_at is null", versionId) == 1) {
                    destroyed++;
                    perDocument.computeIfAbsent(version.getDocumentId(), k -> new ArrayList<>()).add(version.getVersionNumber());
                    titles.putIfAbsent(version.getDocumentId(), titleOf(version.getDocumentId()));
                }
            } catch (RuntimeException e) {
                log.warn("Could not destroy removed version {} - will try again next run: {}", versionId, e.getMessage());
            }
        }
        perDocument.forEach((documentId, numbers) -> auditService.event(AuditActionCatalog.DOCUMENT_VERSIONS_DESTROYED)
                .tenantId(tenantId).systemActor().objectType(AuditObjectTypes.DOCUMENT).objectId(documentId)
                .resourceName(titles.get(documentId)).outcome(AuditOutcome.SUCCESS)
                .newValue(Map.of("versions", numbers, "why", "grace period of " + graceDays + " days ended")).log());
        return destroyed;
    }

    /** Versions removed by retention that are still restorable, newest removal first (the "Removed versions" tab). */
    public Page<RemovedVersion> removed(Pageable pageable) {
        UUID tenantId = TenantContext.getTenantId();
        int graceDays = settings.forTenant(tenantId).versionGraceDays();
        Long total = jdbc.queryForObject("""
                select count(*) from document_versions v join documents d on d.document_id = v.document_id
                where v.tenant_id = ? and v.retention_removed_at is not null and v.purged_at is null and not d.is_deleted
                """, Long.class, tenantId);
        List<RemovedVersion> rows = jdbc.query("""
                select v.version_id, v.document_id, d.title, v.version_number, v.original_filename, v.size_bytes,
                       v.retention_removed_at
                from document_versions v join documents d on d.document_id = v.document_id
                where v.tenant_id = ? and v.retention_removed_at is not null and v.purged_at is null and not d.is_deleted
                order by v.retention_removed_at desc, v.version_number desc limit ? offset ?
                """, (rs, i) -> {
                    OffsetDateTime removedAt = rs.getObject("retention_removed_at", OffsetDateTime.class);
                    return new RemovedVersion(rs.getObject("version_id", UUID.class), rs.getObject("document_id", UUID.class),
                            rs.getString("title"), rs.getInt("version_number"), rs.getString("original_filename"),
                            rs.getLong("size_bytes"), removedAt, removedAt.plusDays(graceDays));
                }, tenantId, pageable.getPageSize(), pageable.getOffset());
        return new PageImpl<>(rows, pageable, total == null ? 0 : total);
    }

    /**
     * Brings a removed version back into the version history - and exempts it from further trimming, so the next run does not
     * remove it again. Only possible until its files are destroyed.
     */
    public void restore(UUID versionId) {
        UUID tenantId = TenantContext.getTenantId();
        Map<String, Object> row = jdbc.queryForList("""
                select v.document_id, v.version_number, d.title from document_versions v
                join documents d on d.document_id = v.document_id
                where v.version_id = ? and v.tenant_id = ? and v.retention_removed_at is not null and v.purged_at is null
                  and not d.is_deleted
                """, versionId, tenantId).stream().findFirst().orElse(null);
        if (row == null || jdbc.update("""
                update document_versions set is_deleted = false, deleted_at = null, retention_removed_at = null, retention_keep = true
                where version_id = ? and tenant_id = ? and retention_removed_at is not null and purged_at is null
                """, versionId, tenantId) == 0) {
            throw ComplianceException.conflict("VERSION_NOT_RESTORABLE",
                    "That version can't be restored - it wasn't removed by retention, or its files have already been destroyed.");
        }
        auditService.event(AuditActionCatalog.RETENTION_VERSION_RESTORED).objectType(AuditObjectTypes.DOCUMENT)
                .objectId((UUID) row.get("document_id")).resourceName((String) row.get("title")).outcome(AuditOutcome.SUCCESS)
                .newValue(Map.of("version", row.get("version_number"), "keptFromNowOn", true)).log();
    }

    private String titleOf(UUID documentId) {
        List<String> titles = jdbc.queryForList("select title from documents where document_id = ?", String.class, documentId);
        return titles.isEmpty() ? null : titles.get(0);
    }
}
