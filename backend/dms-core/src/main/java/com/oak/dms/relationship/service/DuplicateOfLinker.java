package com.oak.dms.relationship.service;

import com.oak.dms.document.Document;
import com.oak.dms.document.DocumentRepository;
import com.oak.dms.folder.DocumentAccessService;
import com.oak.dms.relationship.entity.DocumentRelationship;
import com.oak.dms.relationship.event.DuplicateKeptEvent;
import com.oak.dms.relationship.seed.RelationshipTypeCatalog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * The automatic "Duplicate of" (D3): the new copy is the source, the original the target. A system
 * action - the uploader needs no DRM permission - but only to something they can open: the original is
 * checked again here, as the uploader, because it may have been deleted or locked down since the
 * match. Its own transaction (the upload's has committed); nothing is created when the type is off.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DuplicateOfLinker {

    private final DocumentRepository documents;
    private final DocumentAccessService documentAccess;
    private final RelationshipService relationshipService;
    private final JdbcTemplate jdbc;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void link(DuplicateKeptEvent event) {
        Document original = documents.findByDocumentIdAndTenantIdAndDeletedFalse(event.originalDocumentId(), event.tenantId())
                .orElse(null);
        if (original == null || original.getPurgedAt() != null || original.isTemplate()) {
            log.info("Not linking {} as a duplicate: the original {} is gone", event.newDocumentId(), event.originalDocumentId());
            return;
        }
        if (!documentAccess.forUser(event.tenantId(), event.uploadedBy()).canSee(original)) {
            log.info("Not linking {} as a duplicate: {} can't open the original {}", event.newDocumentId(),
                    event.uploadedBy(), event.originalDocumentId());
            return;
        }
        // A file bulk import added belongs to an import batch; the links of one batch share its id.
        List<UUID> batches = jdbc.queryForList("select import_batch_id from import_items where scan_result_id = ?",
                UUID.class, event.scanResultId());
        UUID batchId = batches.isEmpty() ? null : batches.get(0);
        DocumentRelationship link = relationshipService.createAutomatic(event.tenantId(), event.uploadedBy(),
                RelationshipTypeCatalog.DUPLICATE_OF, event.newDocumentId(), event.originalDocumentId(),
                DocumentRelationship.ORIGIN_DUPLICATE_DETECTION, batchId);
        if (link == null) {
            log.info("No \"Duplicate of\" link made for {} -> {} (type off, or already linked)", event.newDocumentId(),
                    event.originalDocumentId());
        }
    }
}
