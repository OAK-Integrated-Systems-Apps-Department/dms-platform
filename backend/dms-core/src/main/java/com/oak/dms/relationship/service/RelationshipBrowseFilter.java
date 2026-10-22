package com.oak.dms.relationship.service;

import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.document.Document;
import com.oak.dms.document.DocumentRepository;
import com.oak.dms.folder.DocumentAccessService;
import com.oak.dms.folder.FolderAccessLevel;
import com.oak.dms.relationship.dto.RelationshipFilter;
import com.oak.dms.relationship.entity.RelationshipType;
import com.oak.dms.relationship.exception.RelationshipException;
import com.oak.dms.relationship.repository.RelationshipTypeRepository;
import com.oak.dms.security.auth.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.UUID;

/**
 * Turns the browse endpoint's {@code relationshipType}, {@code relatedTo} and {@code direction}
 * parameters into a {@link RelationshipFilter}. The related document must be one the caller can open
 * (404 otherwise), so the filter can't be used to find out whether a hidden document has links. The
 * documents it returns are filtered for access by the browse query itself.
 */
@Service
@RequiredArgsConstructor
public class RelationshipBrowseFilter {

    private final RelationshipTypeRepository types;
    private final DocumentRepository documents;
    private final DocumentAccessService documentAccess;

    /**
     * @param direction {@code source}: documents that are the source of a link to {@code relatedTo} (for
     *                  Supersedes: the ones that supersede it); {@code target}: the ones it points at;
     *                  {@code any} (default). A two-way type has no direction and always means {@code any}.
     * @return null when there is nothing to filter by
     */
    @Transactional(readOnly = true)
    public RelationshipFilter resolve(String typeCode, UUID relatedTo, String direction) {
        boolean noType = typeCode == null || typeCode.isBlank();
        if (noType && relatedTo == null) {
            return null;
        }
        UUID tenantId = TenantContext.getTenantId();
        RelationshipType type = null;
        if (!noType) {
            type = types.findByTenantIdAndCodeAndDeletedFalse(tenantId, typeCode.strip().toUpperCase(Locale.ROOT))
                    .orElseThrow(() -> new ResourceNotFoundException("Relationship type", typeCode));
        }
        if (relatedTo != null) {
            Document related = documents.findByDocumentIdAndTenantIdAndDeletedFalse(relatedTo, tenantId)
                    .orElseThrow(() -> new ResourceNotFoundException("Document", relatedTo));
            documentAccess.require(documentAccess.forCaller(), related, FolderAccessLevel.view);
        }
        String dir = direction == null || direction.isBlank() ? "any" : direction.strip().toLowerCase(Locale.ROOT);
        if (!dir.equals("source") && !dir.equals("target") && !dir.equals("any")) {
            throw RelationshipException.invalid("RELATIONSHIP_DIRECTION_INVALID", "direction must be source, target or any.");
        }
        boolean symmetric = type != null && type.isSymmetric();
        boolean asSource = symmetric || !dir.equals("target");
        boolean asTarget = symmetric || !dir.equals("source");
        return new RelationshipFilter(type == null ? null : type.getRelationshipTypeId(), relatedTo, asSource, asTarget);
    }
}
