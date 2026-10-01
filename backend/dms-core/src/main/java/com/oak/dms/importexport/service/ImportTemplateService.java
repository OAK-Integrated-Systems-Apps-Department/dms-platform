package com.oak.dms.importexport.service;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.importexport.dto.ImportTemplateFieldResponse;
import com.oak.dms.importexport.dto.ImportTemplateRequest;
import com.oak.dms.importexport.dto.ImportTemplateResponse;
import com.oak.dms.importexport.entity.ImportTemplate;
import com.oak.dms.importexport.exception.BulkImportException;
import com.oak.dms.importexport.exception.InactiveDocumentTypeException;
import com.oak.dms.importexport.exception.InvalidImportTemplateException;
import com.oak.dms.importexport.repository.ImportTemplateRepository;
import com.oak.dms.importexport.template.ImportTemplateWorkbook;
import com.oak.dms.importexport.template.ImportTemplateWorkbook.TemplateLayout;
import com.oak.dms.importexport.validation.ImportField;
import com.oak.dms.importexport.validation.ImportUsers;
import com.oak.dms.importexport.validation.ImportValueConverter;
import com.oak.dms.metadata.dto.DocumentTypeSchemaResponse;
import com.oak.dms.metadata.dto.DocumentTypeSchemaResponse.SchemaField;
import com.oak.dms.metadata.dto.MetadataFieldOptionResponse;
import com.oak.dms.metadata.dto.ValidationError;
import com.oak.dms.metadata.entity.DocumentType;
import com.oak.dms.metadata.repository.DocumentTypeRepository;
import com.oak.dms.metadata.service.DocumentTypeFieldService;
import com.oak.dms.metadata.service.MetadataValidationService;
import com.oak.dms.security.auth.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A tenant's saved import templates (FIE "Manage Import Templates"): named,
 * per document type, choosing which fields are columns - in what order,
 * under which header - and default values for fields left out or blank.
 * <p>
 * Templates follow the document type's schema rather than pinning a
 * version: {@link #layout} builds the download from the CURRENT schema,
 * dropping columns whose field is gone and adding any mandatory field the
 * template neither has a column nor a default for. Defaults are checked on
 * save exactly like an imported cell (type conversion, then the field's
 * rules), so a default can never be what makes every row fail.
 * Needs {@link TenantContext}.
 */
@Service
@RequiredArgsConstructor
public class ImportTemplateService {

    static final int MAX_HEADER_LENGTH = 100;

    private final ImportTemplateRepository repository;
    private final DocumentTypeRepository documentTypeRepository;
    private final DocumentTypeFieldService documentTypeFieldService;
    private final MetadataValidationService metadataValidationService;
    private final ImportValueConverter converter;
    private final ImportUsers importUsers;
    private final AuditService auditService;

    // ------------------------------------------------------------- reads

    /** @param documentTypeId only that type's templates; null for all */
    @Transactional(readOnly = true)
    public List<ImportTemplateResponse> list(UUID documentTypeId) {
        UUID tenantId = TenantContext.getTenantId();
        List<ImportTemplate> templates = documentTypeId == null
                ? repository.findByTenantIdAndDeletedFalseOrderByNameAsc(tenantId)
                : repository.findByTenantIdAndDocumentTypeIdAndDeletedFalseOrderByNameAsc(tenantId, documentTypeId);
        Map<UUID, DocumentType> types = new LinkedHashMap<>();
        Map<UUID, Set<String>> keysByType = new LinkedHashMap<>();
        return templates.stream()
                .map(t -> toResponse(t,
                        types.computeIfAbsent(t.getDocumentTypeId(), id -> documentTypeRepository.findById(id).orElse(null)),
                        keysByType.computeIfAbsent(t.getDocumentTypeId(), this::currentKeys)))
                .toList();
    }

    @Transactional(readOnly = true)
    public ImportTemplateResponse get(UUID templateId) {
        ImportTemplate template = own(templateId);
        return toResponse(template, documentTypeRepository.findById(template.getDocumentTypeId()).orElse(null),
                currentKeys(template.getDocumentTypeId()));
    }

    /** The fields a template for this type can use, in form order - for the template editor. */
    @Transactional(readOnly = true)
    public List<ImportTemplateFieldResponse> fields(UUID documentTypeId) {
        activeType(documentTypeId);
        return ImportTemplateWorkbook.templateFields(documentTypeFieldService.getSchema(documentTypeId)).stream()
                .map(f -> new ImportTemplateFieldResponse(f.fieldKey(), f.label(), f.dataType(), f.mandatory(),
                        f.helpText(), optionValues(f)))
                .toList();
    }

    // ------------------------------------------------------------ writes

    @Transactional
    public ImportTemplateResponse create(ImportTemplateRequest request) {
        if (request.documentTypeId() == null) {
            throw invalid(List.of("Choose the document type the template is for."));
        }
        DocumentType type = activeType(request.documentTypeId());
        DocumentTypeSchemaResponse schema = documentTypeFieldService.getSchema(type.getDocumentTypeId());
        String name = request.name().trim();
        checkNameFree(type.getDocumentTypeId(), name, null);
        Validated valid = validate(request, schema);

        ImportTemplate saved = repository.save(ImportTemplate.builder()
                .tenantId(TenantContext.getTenantId())
                .documentTypeId(type.getDocumentTypeId())
                .name(name)
                .description(blankToNull(request.description()))
                .columns(valid.columns())
                .defaults(valid.defaults())
                .createdBy(TenantContext.getUserId())
                .build());
        audit(AuditActionCatalog.IMPORT_TEMPLATE_CREATED, saved, null);
        return toResponse(saved, type, currentKeys(type.getDocumentTypeId()));
    }

    @Transactional
    public ImportTemplateResponse update(UUID templateId, ImportTemplateRequest request) {
        ImportTemplate template = own(templateId);
        if (request.documentTypeId() != null && !request.documentTypeId().equals(template.getDocumentTypeId())) {
            throw invalid(List.of("A template can't be moved to another document type - create a new one."));
        }
        DocumentType type = activeType(template.getDocumentTypeId());
        DocumentTypeSchemaResponse schema = documentTypeFieldService.getSchema(type.getDocumentTypeId());
        String name = request.name().trim();
        checkNameFree(type.getDocumentTypeId(), name, template);
        Validated valid = validate(request, schema);

        Map<String, Object> before = snapshot(template);
        template.setName(name);
        template.setDescription(blankToNull(request.description()));
        template.setColumns(valid.columns());
        template.setDefaults(valid.defaults());
        template.setUpdatedBy(TenantContext.getUserId());
        template.setUpdatedAt(OffsetDateTime.now());
        ImportTemplate saved = repository.save(template);
        audit(AuditActionCatalog.IMPORT_TEMPLATE_UPDATED, saved, before);
        return toResponse(saved, type, currentKeys(type.getDocumentTypeId()));
    }

    /**
     * Soft delete. Files already downloaded from it then can't be uploaded
     * (their defaults are gone) - the upload says to download a fresh one.
     */
    @Transactional
    public void delete(UUID templateId) {
        ImportTemplate template = own(templateId);
        template.setDeleted(true);
        template.setDeletedAt(OffsetDateTime.now());
        template.setUpdatedBy(TenantContext.getUserId());
        template.setUpdatedAt(OffsetDateTime.now());
        repository.save(template);
        audit(AuditActionCatalog.IMPORT_TEMPLATE_DELETED, template, snapshot(template));
    }

    // ------------------------------------------------------- use in imports

    public ImportTemplate requireForDownload(UUID templateId) {
        ImportTemplate template = own(templateId);
        activeType(template.getDocumentTypeId());
        return template;
    }

    /**
     * The download's columns from the CURRENT schema: the template's columns
     * whose field still exists, in its order and headers, then any mandatory
     * field it has neither a column nor a default for (so the file can
     * always be filled in completely).
     */
    public TemplateLayout layout(ImportTemplate template, DocumentTypeSchemaResponse schema) {
        Map<String, SchemaField> current = new LinkedHashMap<>();
        ImportTemplateWorkbook.templateFields(schema).forEach(f -> current.put(f.fieldKey(), f));
        List<SchemaField> fields = new ArrayList<>();
        Map<String, String> headers = new LinkedHashMap<>();
        for (ImportTemplate.Column column : template.getColumns()) {
            SchemaField field = current.get(column.fieldKey());
            if (field != null) {
                fields.add(field);
                if (column.header() != null && !column.header().isBlank()) {
                    headers.put(field.fieldKey(), column.header());
                }
            }
        }
        for (SchemaField field : current.values()) {
            if (field.mandatory() && !fields.contains(field) && !template.getDefaults().containsKey(field.fieldKey())) {
                fields.add(field);
            }
        }
        return new TemplateLayout(template.getImportTemplateId(), fields, headers);
    }

    /**
     * The defaults to apply to an uploaded file made from template
     * {@code templateId} for {@code documentTypeId}.
     *
     * @throws InvalidImportTemplateException if the template is gone or for another type
     */
    @Transactional(readOnly = true)
    public Map<String, String> defaultsForUpload(UUID templateId, UUID documentTypeId) {
        Optional<ImportTemplate> template = repository.findByImportTemplateIdAndTenantIdAndDeletedFalse(templateId,
                TenantContext.getTenantId());
        if (template.isEmpty() || !template.get().getDocumentTypeId().equals(documentTypeId)) {
            throw new InvalidImportTemplateException("IMPORT_TEMPLATE_REMOVED",
                    "The saved template this file was made from no longer exists. Download a fresh template "
                            + "and copy your rows into it.");
        }
        return Map.copyOf(template.get().getDefaults());
    }

    // ------------------------------------------------------------ helpers

    private record Validated(List<ImportTemplate.Column> columns, Map<String, String> defaults) {
    }

    private Validated validate(ImportTemplateRequest request, DocumentTypeSchemaResponse schema) {
        Map<String, SchemaField> fields = new LinkedHashMap<>();
        ImportTemplateWorkbook.templateFields(schema).forEach(f -> fields.put(f.fieldKey(), f));
        List<String> problems = new ArrayList<>();

        List<ImportTemplate.Column> columns = new ArrayList<>();
        Set<String> seenKeys = new HashSet<>();
        Set<String> seenHeaders = new HashSet<>(Set.of(ImportTemplateWorkbook.FILENAME_KEY,
                ImportTemplateWorkbook.TITLE_KEY, "file name"));
        for (ImportTemplateRequest.Column column : request.columns()) {
            SchemaField field = fields.get(column.fieldKey());
            if (field == null) {
                problems.add("\"" + column.fieldKey() + "\" isn't a field of this document type.");
                continue;
            }
            if (!seenKeys.add(field.fieldKey())) {
                problems.add(field.label() + " is listed twice.");
                continue;
            }
            String header = blankToNull(column.header());
            String shown = header != null ? header : field.label();
            if (shown.length() > MAX_HEADER_LENGTH) {
                problems.add("The header for " + field.label() + " is longer than " + MAX_HEADER_LENGTH + " characters.");
            } else if (!seenHeaders.add(shown.toLowerCase(Locale.ROOT))) {
                problems.add("Two columns are called \"" + shown + "\" - headers must be different.");
            }
            columns.add(new ImportTemplate.Column(field.fieldKey(), header));
        }

        Map<String, String> defaults = new LinkedHashMap<>();
        Map<String, Object> converted = new LinkedHashMap<>();
        Function<String, Optional<UUID>> users = importUsers.activeUserByEmail(TenantContext.getTenantId());
        if (request.defaults() != null) {
            request.defaults().forEach((key, raw) -> {
                SchemaField field = fields.get(key);
                if (field == null) {
                    problems.add("\"" + key + "\" isn't a field of this document type.");
                    return;
                }
                if (raw == null || raw.isBlank()) {
                    return; // no default
                }
                ImportValueConverter.Result result = converter.convert(new ImportField(field.fieldId(), key,
                        field.label(), field.dataType(), field.mandatory(), field.hidden()), raw, users);
                if (result.error() != null) {
                    problems.add("Default for " + field.label() + ": " + result.error().message());
                    return;
                }
                defaults.put(key, raw.trim());
                converted.put(key, result.value());
            });
        }
        if (!converted.isEmpty()) {
            for (ValidationError error : metadataValidationService.validate(schema.documentTypeId(),
                    schema.schemaVersion(), converted)) {
                if (converted.containsKey(error.fieldKey()) && "error".equals(error.severity())) {
                    problems.add("Default for " + fields.get(error.fieldKey()).label() + ": " + error.message());
                    defaults.remove(error.fieldKey()); // a rejected default covers nothing
                }
            }
        }

        List<String> uncovered = fields.values().stream()
                .filter(f -> f.mandatory() && !seenKeys.contains(f.fieldKey()) && !defaults.containsKey(f.fieldKey()))
                .map(SchemaField::label)
                .toList();
        if (!uncovered.isEmpty()) {
            problems.add("Required fields need a column or a default value: " + String.join(", ", uncovered) + ".");
        }
        if (!problems.isEmpty()) {
            throw invalid(problems);
        }
        return new Validated(columns, defaults);
    }

    private void checkNameFree(UUID documentTypeId, String name, ImportTemplate self) {
        if (repository.existsByTenantIdAndDocumentTypeIdAndNameIgnoreCaseAndDeletedFalse(TenantContext.getTenantId(),
                documentTypeId, name) && (self == null || !self.getName().equalsIgnoreCase(name))) {
            throw BulkImportException.conflict("IMPORT_TEMPLATE_NAME_TAKEN",
                    "This document type already has a template called \"" + name + "\".");
        }
    }

    private DocumentType activeType(UUID documentTypeId) {
        DocumentType type = documentTypeRepository
                .findByDocumentTypeIdAndTenantIdAndDeletedFalse(documentTypeId, TenantContext.getTenantId())
                .orElseThrow(() -> new ResourceNotFoundException("DocumentType", documentTypeId));
        if (type.getStatus() != DocumentType.DocumentTypeStatus.active) {
            throw new InactiveDocumentTypeException("Document type '" + type.getTypeName()
                    + "' is inactive and cannot be used for imports.");
        }
        return type;
    }

    private ImportTemplate own(UUID templateId) {
        return repository.findByImportTemplateIdAndTenantIdAndDeletedFalse(templateId, TenantContext.getTenantId())
                .orElseThrow(() -> new ResourceNotFoundException("ImportTemplate", templateId));
    }

    private Set<String> currentKeys(UUID documentTypeId) {
        try {
            return ImportTemplateWorkbook.templateFields(documentTypeFieldService.getSchema(documentTypeId)).stream()
                    .map(SchemaField::fieldKey).collect(Collectors.toSet());
        } catch (RuntimeException e) {
            return Set.of();
        }
    }

    private static ImportTemplateResponse toResponse(ImportTemplate t, DocumentType type, Set<String> currentKeys) {
        List<String> missing = t.getColumns().stream().map(ImportTemplate.Column::fieldKey)
                .filter(key -> !currentKeys.contains(key)).toList();
        return new ImportTemplateResponse(t.getImportTemplateId(), t.getDocumentTypeId(),
                type != null ? type.getTypeName() : null, t.getName(), t.getDescription(),
                t.getColumns().stream().map(c -> new ImportTemplateRequest.Column(c.fieldKey(), c.header())).toList(),
                t.getDefaults(), missing, t.getCreatedAt(), t.getUpdatedAt());
    }

    private static List<String> optionValues(SchemaField field) {
        return field.options() == null ? List.of()
                : field.options().stream().map(MetadataFieldOptionResponse::optionValue).toList();
    }

    private static Map<String, Object> snapshot(ImportTemplate t) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("name", t.getName());
        values.put("documentTypeId", t.getDocumentTypeId());
        values.put("columns", t.getColumns().stream().map(ImportTemplate.Column::fieldKey).toList());
        values.put("defaults", t.getDefaults());
        return values;
    }

    private void audit(String action, ImportTemplate template, Map<String, Object> before) {
        var event = auditService.event(action)
                .objectType(AuditObjectTypes.IMPORT_TEMPLATE)
                .objectId(template.getImportTemplateId())
                .resourceName(template.getName())
                .outcome(AuditOutcome.SUCCESS)
                .newValue(snapshot(template));
        if (before != null) {
            event.previousValue(before);
        }
        event.log();
    }

    private static BulkImportException invalid(List<String> problems) {
        return BulkImportException.badRequest("IMPORT_TEMPLATE_INVALID", String.join(" ", problems));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
