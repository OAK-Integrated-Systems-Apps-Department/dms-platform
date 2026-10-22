package com.oak.dms.relationship.service;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.relationship.dto.CreateRelationshipTypeRequest;
import com.oak.dms.relationship.dto.RelationshipTypeResponse;
import com.oak.dms.relationship.dto.UpdateRelationshipTypeRequest;
import com.oak.dms.relationship.entity.RelationshipType;
import com.oak.dms.relationship.exception.RelationshipException;
import com.oak.dms.relationship.helper.CycleDetector;
import com.oak.dms.relationship.repository.DocumentRelationshipRepository;
import com.oak.dms.relationship.repository.RelationshipTypeRepository;
import com.oak.dms.security.auth.AuthorizationService;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.security.rbac.catalog.PermissionCodes;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Admin-editable relationship types. The ten seeded ones are system types: renamed, relabelled,
 * disabled - never deleted, and their direction and loop rule are fixed. A custom type can be
 * deleted only while nothing has ever used it; one in use is disabled instead, which blocks new
 * links of that type and leaves the existing ones visible and working.
 */
@Service
@RequiredArgsConstructor
public class RelationshipTypeService {

    private final RelationshipTypeRepository types;
    private final DocumentRelationshipRepository relationships;
    private final CycleDetector cycleDetector;
    private final AuthorizationService authorizationService;
    private final AuditService auditService;

    /**
     * Enabled types for the add-link dialog (anyone who may create links), or every type with its
     * usage count for the admin screen (needs a configure-types permission).
     */
    @Transactional(readOnly = true)
    public List<RelationshipTypeResponse> list(boolean includeDisabled) {
        UUID tenantId = TenantContext.getTenantId();
        if (includeDisabled && !authorizationService.hasAnyPermission(PermissionCodes.DRM_TYPES_CREATE,
                PermissionCodes.DRM_TYPES_UPDATE, PermissionCodes.DRM_TYPES_DELETE)) {
            throw new AccessDeniedException("Listing disabled relationship types needs a configure-types permission");
        }
        List<RelationshipType> rows = includeDisabled
                ? types.findByTenantIdAndDeletedFalseOrderByDisplayOrderAscLabelAsc(tenantId)
                : types.findByTenantIdAndEnabledTrueAndDeletedFalseOrderByDisplayOrderAscLabelAsc(tenantId);
        Map<UUID, Long> usage = includeDisabled ? usageCounts(tenantId) : Map.of();
        return rows.stream().map(t -> toResponse(t, usage.getOrDefault(t.getRelationshipTypeId(), 0L))).toList();
    }

    @Transactional
    public RelationshipTypeResponse create(CreateRelationshipTypeRequest request) {
        UUID tenantId = TenantContext.getTenantId();
        String label = request.label().strip();
        if (label.isEmpty()) {
            throw RelationshipException.invalid("RELATIONSHIP_TYPE_INVALID", "The label can't be empty.");
        }
        boolean symmetric = RelationshipType.SYMMETRIC.equals(request.directionality());
        boolean cycles = Boolean.TRUE.equals(request.preventsCycles());
        if (symmetric && cycles) {
            throw RelationshipException.invalid("RELATIONSHIP_TYPE_INVALID",
                    "A two-way type has no direction, so it can't prevent loops.");
        }
        String inverse = symmetric || request.inverseLabel() == null || request.inverseLabel().isBlank()
                ? label : request.inverseLabel().strip();
        String code = codeFor(label);
        if (types.existsByTenantIdAndCodeAndDeletedFalse(tenantId, code) || labelTaken(tenantId, label, null)) {
            throw RelationshipException.conflict("RELATIONSHIP_TYPE_CODE_TAKEN",
                    "There is already a relationship type called \"" + label + "\".");
        }
        UUID userId = TenantContext.getUserId();
        RelationshipType saved = types.saveAndFlush(RelationshipType.builder()
                .tenantId(tenantId)
                .code(code)
                .category(request.category() == null || request.category().isBlank() ? "custom" : request.category().strip())
                .label(label)
                .inverseLabel(inverse)
                .directionality(request.directionality())
                .preventsCycles(cycles)
                .system(false)
                .enabled(true)
                .displayOrder(nextDisplayOrder(tenantId))
                .createdBy(userId)
                .build());
        audit(AuditActionCatalog.RELATIONSHIP_TYPE_CREATED, saved, null, snapshot(saved));
        return toResponse(saved, 0);
    }

    @Transactional
    public RelationshipTypeResponse update(UUID typeId, UpdateRelationshipTypeRequest request) {
        UUID tenantId = TenantContext.getTenantId();
        RelationshipType type = types.lockActive(typeId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Relationship type", typeId));
        Map<String, Object> before = snapshot(type);

        boolean wasEnabled = type.isEnabled();
        boolean otherChange = false;

        if (request.directionality() != null && !request.directionality().equals(type.getDirectionality())) {
            if (type.isSystem()) {
                throw RelationshipException.invalid("RELATIONSHIP_TYPE_SYSTEM_FIXED",
                        "The direction of a built-in type can't be changed.");
            }
            if (relationships.countAllByType(tenantId, typeId) > 0) {
                throw RelationshipException.conflict("RELATIONSHIP_TYPE_IN_USE",
                        "The direction can't change once documents have been linked with this type.");
            }
            type.setDirectionality(request.directionality());
            if (type.isSymmetric()) {
                type.setPreventsCycles(false);
                type.setInverseLabel(type.getLabel());
            }
            otherChange = true;
        }
        if (request.preventsCycles() != null && request.preventsCycles() != type.isPreventsCycles()) {
            if (type.isSystem()) {
                throw RelationshipException.invalid("RELATIONSHIP_TYPE_SYSTEM_FIXED",
                        "The loop rule of a built-in type can't be changed.");
            }
            if (request.preventsCycles()) {
                if (type.isSymmetric()) {
                    throw RelationshipException.invalid("RELATIONSHIP_TYPE_INVALID",
                            "A two-way type has no direction, so it can't prevent loops.");
                }
                try {
                    if (cycleDetector.findAnyCycle(tenantId, typeId).isPresent()) {
                        throw RelationshipException.conflict("RELATIONSHIP_TYPE_HAS_CYCLE",
                                "Documents are already linked in a loop with this type; remove it first.");
                    }
                } catch (IllegalStateException tooMany) {
                    throw RelationshipException.conflict("RELATIONSHIP_TYPE_TOO_LARGE",
                            "There are too many links of this type to check for loops, so loop prevention can't be switched on.");
                }
            }
            type.setPreventsCycles(request.preventsCycles());
            otherChange = true;
        }
        if (request.label() != null && !request.label().strip().equals(type.getLabel())) {
            if (labelTaken(tenantId, request.label().strip(), typeId)) {
                throw RelationshipException.conflict("RELATIONSHIP_TYPE_CODE_TAKEN",
                        "There is already a relationship type called \"" + request.label().strip() + "\".");
            }
            type.setLabel(request.label().strip());
            if (type.isSymmetric()) {
                type.setInverseLabel(type.getLabel());
            }
            otherChange = true;
        }
        if (request.inverseLabel() != null && !type.isSymmetric()
                && !request.inverseLabel().strip().equals(type.getInverseLabel())) {
            type.setInverseLabel(request.inverseLabel().strip());
            otherChange = true;
        }
        if (request.displayOrder() != null && request.displayOrder() != type.getDisplayOrder()) {
            type.setDisplayOrder(request.displayOrder());
            otherChange = true;
        }
        if (request.enabled() != null) {
            type.setEnabled(request.enabled());
        }
        type.setUpdatedBy(TenantContext.getUserId());
        type.setUpdatedAt(OffsetDateTime.now());
        types.saveAndFlush(type);

        Map<String, Object> after = snapshot(type);
        if (otherChange) {
            audit(AuditActionCatalog.RELATIONSHIP_TYPE_UPDATED, type, before, after);
        }
        if (wasEnabled != type.isEnabled()) {
            audit(type.isEnabled() ? AuditActionCatalog.RELATIONSHIP_TYPE_ENABLED : AuditActionCatalog.RELATIONSHIP_TYPE_DISABLED,
                    type, before, after);
        }
        return toResponse(type, usageCounts(tenantId).getOrDefault(typeId, 0L));
    }

    @Transactional
    public void delete(UUID typeId) {
        UUID tenantId = TenantContext.getTenantId();
        RelationshipType type = types.lockActive(typeId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Relationship type", typeId));
        if (type.isSystem()) {
            throw RelationshipException.conflict("RELATIONSHIP_TYPE_SYSTEM",
                    "A built-in relationship type can be disabled but not deleted.");
        }
        if (relationships.countAllByType(tenantId, typeId) > 0) {
            throw RelationshipException.conflict("RELATIONSHIP_TYPE_IN_USE",
                    "Documents have been linked with this type, so it can only be disabled.");
        }
        Map<String, Object> before = snapshot(type);
        type.setDeleted(true);
        type.setDeletedAt(OffsetDateTime.now());
        type.setEnabled(false);
        type.setUpdatedBy(TenantContext.getUserId());
        type.setUpdatedAt(OffsetDateTime.now());
        types.saveAndFlush(type);
        audit(AuditActionCatalog.RELATIONSHIP_TYPE_DELETED, type, before, null);
    }

    // ---------------------------------------------------------------- helpers

    private Map<UUID, Long> usageCounts(UUID tenantId) {
        Map<UUID, Long> counts = new HashMap<>();
        for (Object[] row : relationships.activeCountsPerType(tenantId)) {
            counts.put((UUID) row[0], ((Number) row[1]).longValue());
        }
        return counts;
    }

    /** Two types with the same name would be indistinguishable in the add-link dialog. */
    private boolean labelTaken(UUID tenantId, String label, UUID exceptTypeId) {
        return types.findByTenantIdAndDeletedFalseOrderByDisplayOrderAscLabelAsc(tenantId).stream()
                .anyMatch(t -> !t.getRelationshipTypeId().equals(exceptTypeId) && t.getLabel().equalsIgnoreCase(label));
    }

    private int nextDisplayOrder(UUID tenantId) {
        return types.findByTenantIdAndDeletedFalseOrderByDisplayOrderAscLabelAsc(tenantId).stream()
                .mapToInt(RelationshipType::getDisplayOrder).max().orElse(0) + 10;
    }

    /** Upper-snake key from the label; custom types only (the seeded ones have fixed codes). */
    static String codeFor(String label) {
        String code = label.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (code.isEmpty()) {
            throw RelationshipException.invalid("RELATIONSHIP_TYPE_INVALID", "The label needs at least one letter or digit.");
        }
        return code.length() > 60 ? code.substring(0, 60) : code;
    }

    static RelationshipTypeResponse toResponse(RelationshipType t, long usage) {
        return new RelationshipTypeResponse(t.getRelationshipTypeId(), t.getCode(), t.getCategory(), t.getLabel(),
                t.getInverseLabel(), t.getDirectionality(), t.isPreventsCycles(), t.isSystem(), t.isEnabled(),
                t.getDisplayOrder(), usage);
    }

    private static Map<String, Object> snapshot(RelationshipType t) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("code", t.getCode());
        value.put("label", t.getLabel());
        value.put("inverseLabel", t.getInverseLabel());
        value.put("directionality", t.getDirectionality());
        value.put("preventsCycles", t.isPreventsCycles());
        value.put("enabled", t.isEnabled());
        value.put("displayOrder", t.getDisplayOrder());
        value.put("system", t.isSystem());
        return value;
    }

    private void audit(String action, RelationshipType type, Object previous, Object next) {
        auditService.event(action)
                .objectType(AuditObjectTypes.RELATIONSHIP_TYPE)
                .objectId(type.getRelationshipTypeId())
                .resourceName(type.getLabel())
                .outcome(AuditOutcome.SUCCESS)
                .previousValue(previous)
                .newValue(next)
                .log();
    }
}
