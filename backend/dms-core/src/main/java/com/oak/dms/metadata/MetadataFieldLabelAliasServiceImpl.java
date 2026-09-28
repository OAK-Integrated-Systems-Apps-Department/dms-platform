package com.oak.dms.metadata;

import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.metadata.dto.DocumentTypeFieldLabelAliasesResponse;
import com.oak.dms.metadata.dto.ReplaceLabelAliasesRequest;
import com.oak.dms.security.auth.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * See {@link MetadataFieldLabelAliasService}.
 * <p>
 * A type-scoped list can only be read or written for a field that is
 * ACTIVE-mapped into the document type's current schema version - anything
 * else (unknown/other-tenant document type or field, a field only mapped in
 * an archived version, or never mapped) 404s, so the endpoint can't be used
 * to park hints on a field the type doesn't actually use, or to probe
 * another tenant's ids. The other half of that invariant is
 * {@link #deleteTypeScopedAliasesOfUnmappedFields}: a publish that drops a
 * field from the type deletes that field's type-scoped hints, so nothing
 * these endpoints 404 on is ever left behind. Leftovers would NOT be inert -
 * ocr-worker puts every hint scoped to a document's type into its
 * stop-label set, mapped field or not - and with the endpoints 404ing and
 * the Fields tab listing mapped rows only, the admin could neither see nor
 * clear them.
 * <p>
 * No {@code SchemaCacheService#evictTenant} after writes, unlike the other
 * metadata write paths: that cache holds only the resolved VALIDATION
 * schema ({@code MetadataValidationServiceImpl}), which hints are not part
 * of, and ocr-worker reads hints straight from the database on every run -
 * there is nothing cached for a hint change to make stale.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class MetadataFieldLabelAliasServiceImpl implements MetadataFieldLabelAliasService {

    private final MetadataFieldLabelAliasRepository aliasRepository;
    private final MetadataFieldRepository metadataFieldRepository;
    private final DocumentTypeRepository documentTypeRepository;
    private final DocumentTypeFieldRepository documentTypeFieldRepository;

    @Override
    @Transactional(readOnly = true)
    public List<String> listGlobalAliases(UUID tenantId, UUID fieldId) {
        return texts(aliasRepository.findGlobalByField(tenantId, fieldId));
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, List<String>> listGlobalAliasesByField(UUID tenantId, Collection<UUID> fieldIds) {
        if (fieldIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<String>> byField = groupTextsByField(aliasRepository.findGlobalByFields(tenantId, fieldIds));
        fieldIds.forEach(fieldId -> byField.putIfAbsent(fieldId, List.of()));
        return byField;
    }

    @Override
    public List<String> replaceGlobalAliases(UUID tenantId, UUID fieldId, List<String> rawAliases) {
        List<String> normalized = LabelAliasNormalizer.normalize(rawAliases);

        // The field form sends its hint list on every save, hints edited or
        // not - skip the delete/reinsert when nothing actually changed, so an
        // unrelated label/help-text edit doesn't churn these rows (and reset
        // their created_by/created_at) for nothing.
        if (texts(aliasRepository.findGlobalByField(tenantId, fieldId)).equals(normalized)) {
            return normalized;
        }

        aliasRepository.deleteGlobalByField(tenantId, fieldId);
        insertInOrder(tenantId, fieldId, null, normalized);
        log.info("Replaced global OCR label hints of metadata field {} ({} hint(s))", fieldId, normalized.size());
        return normalized;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, List<String>> listTypeScopedAliasesByField(UUID tenantId, UUID documentTypeId) {
        return groupTextsByField(aliasRepository.findTypeScopedByDocumentType(tenantId, documentTypeId));
    }

    @Override
    @Transactional(readOnly = true)
    public DocumentTypeFieldLabelAliasesResponse getTypeScopedAliases(UUID documentTypeId, UUID fieldId) {
        UUID tenantId = TenantContext.getTenantId();
        DocumentType documentType = documentTypeRepository
                .findByDocumentTypeIdAndTenantIdAndDeletedFalse(documentTypeId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("DocumentType", documentTypeId));
        requireActiveMapping(documentType, fieldId, tenantId);
        return new DocumentTypeFieldLabelAliasesResponse(documentTypeId, fieldId,
                texts(aliasRepository.findTypeScopedByField(tenantId, documentTypeId, fieldId)));
    }

    @Override
    public DocumentTypeFieldLabelAliasesResponse replaceTypeScopedAliases(UUID documentTypeId, UUID fieldId,
                                                                          ReplaceLabelAliasesRequest request) {
        UUID tenantId = TenantContext.getTenantId();

        // Row-locks the document_types row for the rest of this transaction -
        // the same lock a publish takes (DocumentTypeFieldServiceImpl#replaceMapping).
        // Without it two admins saving DIFFERENT lists for the same (type,
        // field) at once would both commit: each bulk delete runs against a
        // snapshot that can't see the other's uncommitted inserts, and the
        // unique index only fires when the two lists share a hint text, so
        // the stored list would end up a merge of both with clashing
        // display_order values. The second save now waits here and then
        // replaces the first's committed list, i.e. last save wins. It also
        // orders a save against a concurrent publish, so hints can't land on
        // a field that publish is unmapping. The global list needs no such
        // lock: it is only ever replaced inside MetadataFieldService's
        // create/update of the field itself, whose flushed UPDATE of the
        // metadata_fields row already serializes concurrent saves.
        DocumentType documentType = documentTypeRepository
                .lockByDocumentTypeIdAndTenantId(documentTypeId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("DocumentType", documentTypeId));
        requireActiveMapping(documentType, fieldId, tenantId);
        List<String> normalized = LabelAliasNormalizer.normalize(request.labelAliases());

        aliasRepository.deleteTypeScopedByField(tenantId, documentTypeId, fieldId);
        insertInOrder(tenantId, fieldId, documentTypeId, normalized);
        log.info("Replaced OCR label hints of metadata field {} for document type {} ({} hint(s))",
                fieldId, documentTypeId, normalized.size());
        return new DocumentTypeFieldLabelAliasesResponse(documentTypeId, fieldId, normalized);
    }

    @Override
    public void deleteTypeScopedAliasesOfUnmappedFields(UUID tenantId, UUID documentTypeId,
                                                        Collection<UUID> mappedFieldIds) {
        int deleted = mappedFieldIds.isEmpty()
                ? aliasRepository.deleteTypeScopedByDocumentType(tenantId, documentTypeId)
                : aliasRepository.deleteTypeScopedByDocumentTypeExceptFields(tenantId, documentTypeId, mappedFieldIds);
        if (deleted > 0) {
            log.info("Deleted {} OCR label hint(s) of document type {} scoped to fields no longer mapped into it",
                    deleted, documentTypeId);
        }
    }

    /**
     * Must run after the (bulk, immediately-executed) delete of the same
     * (field, scope) - see the repository's own comment on why that delete
     * can't be a derived deleteBy. Flushed right away so a constraint
     * violation surfaces here as a DataIntegrityViolationException (409 via
     * GlobalExceptionHandler) rather than at commit time.
     */
    private void insertInOrder(UUID tenantId, UUID fieldId, UUID documentTypeId, List<String> aliases) {
        if (aliases.isEmpty()) {
            return;
        }
        UUID userId = TenantContext.getUserId();
        List<MetadataFieldLabelAlias> rows = IntStream.range(0, aliases.size())
                .mapToObj(index -> MetadataFieldLabelAlias.builder()
                        .tenantId(tenantId)
                        .fieldId(fieldId)
                        .documentTypeId(documentTypeId)
                        .aliasText(aliases.get(index))
                        // Same canonical (index+1)*10 sequence as
                        // document_type_fields.display_order on publish.
                        .displayOrder((index + 1) * 10)
                        .createdBy(userId)
                        .build())
                .toList();
        aliasRepository.saveAllAndFlush(rows);
    }

    /** {@code documentType} is already resolved within {@code tenantId} (locked, for a write). */
    private void requireActiveMapping(DocumentType documentType, UUID fieldId, UUID tenantId) {
        UUID documentTypeId = documentType.getDocumentTypeId();
        metadataFieldRepository.findByFieldIdAndTenantIdAndDeletedFalse(fieldId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("MetadataField", fieldId));
        if (!documentTypeFieldRepository.existsActiveMapping(
                documentTypeId, fieldId, tenantId, documentType.getCurrentSchemaVersion())) {
            throw new ResourceNotFoundException("DocumentTypeField", documentTypeId + "/" + fieldId);
        }
    }

    private static List<String> texts(List<MetadataFieldLabelAlias> aliases) {
        return aliases.stream().map(MetadataFieldLabelAlias::getAliasText).toList();
    }

    /** Relies on the repository's "order by fieldId, displayOrder" to keep each list in priority order. */
    private static Map<UUID, List<String>> groupTextsByField(List<MetadataFieldLabelAlias> aliases) {
        return aliases.stream().collect(Collectors.groupingBy(
                MetadataFieldLabelAlias::getFieldId,
                LinkedHashMap::new,
                Collectors.mapping(MetadataFieldLabelAlias::getAliasText, Collectors.toList())));
    }
}
