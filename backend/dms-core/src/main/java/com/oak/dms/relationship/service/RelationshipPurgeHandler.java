package com.oak.dms.relationship.service;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.service.AuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A purged document can't be opened ever again, so the live links to and from it go (DRM-FR-011):
 * each is marked removed (reason {@code target_purged}) and the audit trail keeps a permanent record
 * of what the document was related to and how. Called inside the recycle bin's purge transaction -
 * a purge that fails leaves every link as it was. Soft-deleting a document and restoring it need
 * nothing here: the links just stay.
 * <p>
 * Runs from the purge sweep as well as from a person, so it takes the tenant explicitly and never reads TenantContext.
 */
@Component
@RequiredArgsConstructor
public class RelationshipPurgeHandler {

    private final JdbcTemplate jdbc;
    private final AuditService auditService;

    @Transactional(propagation = Propagation.MANDATORY)
    public int removeLinksOf(UUID tenantId, UUID documentId, String documentTitle) {
        List<Map<String, Object>> links = jdbc.queryForList("""
                select r.relationship_id, r.relationship_type_id, t.code, t.label, t.inverse_label,
                       r.source_document_id, r.target_document_id, sd.title as source_title, td.title as target_title,
                       r.origin, r.batch_id, r.note, r.created_by, r.created_at
                  from document_relationships r
                  join relationship_types t on t.tenant_id = r.tenant_id and t.relationship_type_id = r.relationship_type_id
                  join documents sd on sd.document_id = r.source_document_id
                  join documents td on td.document_id = r.target_document_id
                 where r.tenant_id = ? and r.removed_at is null
                   and (r.source_document_id = ? or r.target_document_id = ?)
                 order by r.created_at
                   for update of r
                """, tenantId, documentId, documentId);
        if (links.isEmpty()) {
            return 0;
        }
        jdbc.update("update document_relationships set removed_at = now(), removed_reason = 'target_purged', updated_at = now()"
                + " where tenant_id = ? and removed_at is null and (source_document_id = ? or target_document_id = ?)",
                tenantId, documentId, documentId);
        for (Map<String, Object> link : links) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("relationshipId", link.get("relationship_id"));
            value.put("typeCode", link.get("code"));
            value.put("typeLabel", link.get("label"));
            value.put("inverseLabel", link.get("inverse_label"));
            value.put("sourceDocumentId", link.get("source_document_id"));
            value.put("sourceTitle", link.get("source_title"));
            value.put("targetDocumentId", link.get("target_document_id"));
            value.put("targetTitle", link.get("target_title"));
            value.put("purgedDocumentId", documentId);
            value.put("purgedDocumentRole", documentId.equals(link.get("source_document_id")) ? "source" : "target");
            value.put("origin", link.get("origin"));
            value.put("batchId", link.get("batch_id"));
            value.put("note", link.get("note"));
            value.put("createdBy", link.get("created_by"));
            value.put("createdAt", String.valueOf(link.get("created_at")));
            auditService.event(AuditActionCatalog.RELATIONSHIP_REMOVED_TARGET_PURGED)
                    .tenantId(tenantId)
                    .objectType(AuditObjectTypes.DOCUMENT_RELATIONSHIP)
                    .objectId((UUID) link.get("relationship_id"))
                    .resourceName(String.valueOf(link.get("label")))
                    .outcome(AuditOutcome.SUCCESS)
                    .reason("Document \"" + documentTitle + "\" was permanently deleted")
                    .newValue(value)
                    .log();
        }
        return links.size();
    }
}
