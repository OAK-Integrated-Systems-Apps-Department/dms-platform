package com.oak.dms.metadata.seed;

import com.oak.dms.metadata.cache.SchemaCacheService;
import com.oak.dms.metadata.entity.DocumentType;
import com.oak.dms.metadata.entity.DocumentTypeSchema;
import com.oak.dms.metadata.entity.MetadataField;
import com.oak.dms.metadata.entity.MetadataFieldOption;
import com.oak.dms.metadata.helper.DisplayOrderNormalizer;
import com.oak.dms.metadata.repository.DocumentTypeRepository;
import com.oak.dms.metadata.repository.DocumentTypeSchemaRepository;
import com.oak.dms.metadata.repository.MetadataFieldOptionRepository;
import com.oak.dms.metadata.repository.MetadataFieldRepository;
import com.oak.dms.security.auth.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Installs the {@link StarterCatalog} into one tenant. Called when a tenant is
 * created, inside that transaction, so a failure here rolls the tenant back
 * too.
 * <p>
 * Idempotent, and never touches the tenant's existing rows - the same rules
 * V37 applies when backfilling tenants that predate this service:
 * <ul>
 * <li>a field or document type whose key/code the tenant already has (live,
 * whatever its status) is skipped; a soft-deleted one doesn't count</li>
 * <li>options are added only to fields this call created</li>
 * <li>a pre-existing field is mapped only if it is active</li>
 * <li>new document types are ordered after the tenant's existing ones</li>
 * </ul>
 * Writes no audit events: the tenant-creation event already covers it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class TenantStarterCatalogService {

    private final MetadataFieldRepository metadataFieldRepository;
    private final MetadataFieldOptionRepository metadataFieldOptionRepository;
    private final DocumentTypeRepository documentTypeRepository;
    private final DocumentTypeSchemaRepository documentTypeSchemaRepository;
    private final SchemaCacheService schemaCacheService;

    public record InstallResult(int fields, int options, int documentTypes, int schemas) {
    }

    public InstallResult install(UUID tenantId) {
        UUID actor = currentUserOrNull();

        Map<String, MetadataField> existingFields = metadataFieldRepository.findAllByTenantIdAndDeletedFalse(tenantId)
                .stream()
                .collect(Collectors.toMap(MetadataField::getFieldKey, Function.identity()));
        Map<String, MetadataField> mappableFields = new HashMap<>();
        List<MetadataField> createdFields = new ArrayList<>();
        for (StarterCatalog.FieldDef def : StarterCatalog.FIELDS) {
            MetadataField existing = existingFields.get(def.key());
            if (existing != null) {
                if (existing.getStatus() == MetadataField.FieldStatus.active) {
                    mappableFields.put(def.key(), existing);
                }
                continue;
            }
            MetadataField field = MetadataField.builder()
                    .tenantId(tenantId)
                    .fieldKey(def.key())
                    .label(def.label())
                    .helpText(def.helpText())
                    .dataType(def.dataType())
                    .systemField(def.systemField())
                    .searchable(true)
                    .ocrExtractable(def.ocrExtractable())
                    .status(MetadataField.FieldStatus.active)
                    .createdBy(actor)
                    .build();
            createdFields.add(field);
            mappableFields.put(def.key(), field);
        }
        metadataFieldRepository.saveAll(createdFields);
        // Options, types and schemas carry no JPA association to what they
        // reference, so with order_inserts Hibernate doesn't know the rows
        // they point at must be inserted first.
        metadataFieldRepository.flush();

        Set<String> createdKeys = createdFields.stream().map(MetadataField::getFieldKey).collect(Collectors.toSet());
        List<MetadataFieldOption> options = StarterCatalog.OPTIONS.stream()
                .filter(def -> createdKeys.contains(def.fieldKey()))
                .map(def -> MetadataFieldOption.builder()
                        .tenantId(tenantId)
                        .fieldId(mappableFields.get(def.fieldKey()).getFieldId())
                        .optionValue(def.value())
                        .optionLabel(def.label())
                        .displayOrder(def.displayOrder())
                        .status(MetadataFieldOption.OptionStatus.active)
                        .createdBy(actor)
                        .build())
                .toList();
        metadataFieldOptionRepository.saveAll(options);

        List<DocumentType> siblings = new ArrayList<>(documentTypeRepository.findAllByTenantOrderedByDisplayOrder(tenantId));
        Set<String> existingCodes = siblings.stream().map(DocumentType::getTypeCode).collect(Collectors.toSet());
        List<DocumentType> createdTypes = new ArrayList<>();
        for (StarterCatalog.TypeDef def : StarterCatalog.TYPES) {
            if (existingCodes.contains(def.code())) {
                continue;
            }
            DocumentType type = DocumentType.builder()
                    .tenantId(tenantId)
                    .typeCode(def.code())
                    .typeName(def.name())
                    .description(def.description())
                    .displayOrder(DisplayOrderNormalizer.appendOrder(siblings, DocumentType::getDisplayOrder))
                    .currentSchemaVersion(1)
                    .requiresWatermark(def.requiresWatermark())
                    .coeditingEnabled(def.coeditingEnabled())
                    .status(DocumentType.DocumentTypeStatus.active)
                    .createdBy(actor)
                    .build();
            siblings.add(type);
            createdTypes.add(type);
        }
        documentTypeRepository.saveAll(createdTypes);
        documentTypeRepository.flush();

        int schemas = 0;
        for (DocumentType type : createdTypes) {
            if (documentTypeSchemaRepository.findByDocumentTypeIdAndTenantIdAndSchemaVersion(
                    type.getDocumentTypeId(), tenantId, type.getCurrentSchemaVersion()).isPresent()) {
                continue;
            }
            List<DocumentTypeSchema.FieldMapping> mappings = StarterCatalog.MAPPINGS.stream()
                    .filter(def -> def.typeCode().equals(type.getTypeCode()) && mappableFields.containsKey(def.fieldKey()))
                    .sorted(Comparator.comparingInt(StarterCatalog.MappingDef::displayOrder))
                    .map(def -> new DocumentTypeSchema.FieldMapping(
                            UUID.randomUUID(),
                            mappableFields.get(def.fieldKey()).getFieldId(),
                            def.mandatory(),
                            false,
                            false,
                            def.displayOrder(),
                            def.fieldGroup(),
                            null))
                    .toList();
            documentTypeSchemaRepository.save(DocumentTypeSchema.builder()
                    .tenantId(tenantId)
                    .documentTypeId(type.getDocumentTypeId())
                    .schemaVersion(type.getCurrentSchemaVersion())
                    .status(DocumentTypeSchema.SchemaStatus.active)
                    .fields(new ArrayList<>(mappings))
                    .publishedBy(actor)
                    .build());
            schemas++;
        }

        schemaCacheService.evictTenant(tenantId);
        InstallResult result = new InstallResult(createdFields.size(), options.size(), createdTypes.size(), schemas);
        log.info("Installed starter catalogue in tenant {}: {}", tenantId, result);
        return result;
    }

    private static UUID currentUserOrNull() {
        try {
            return TenantContext.getUserId();
        } catch (IllegalStateException e) {
            return null;
        }
    }
}
