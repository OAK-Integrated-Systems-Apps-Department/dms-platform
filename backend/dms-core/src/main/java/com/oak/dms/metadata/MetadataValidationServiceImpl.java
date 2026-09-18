package com.oak.dms.metadata;

import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.metadata.cache.SchemaCacheService;
import com.oak.dms.metadata.dto.ValidationError;
import com.oak.dms.security.auth.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * See {@link MetadataValidationService}.
 * <p>
 * MDTM-NFR-002 (validation completes without noticeable delay) is met with a
 * {@link SchemaCacheService} (Redis-backed) cache-aside lookup keyed by
 * {@code (tenantId, documentTypeId, version)}.
 * <p>
 * Only {@code document_type_fields} rows are immutable once a version is
 * published (see {@code DocumentTypeFieldServiceImpl#replaceMapping} -
 * archive-and-reinsert, never edited in place). The {@link MetadataField},
 * {@link MetadataFieldOption} and {@link MetadataFieldValidation} rows also
 * captured into each cached {@link ResolvedField} are NOT immutable: editing
 * a dropdown option, a validation rule, or a field's own attributes changes
 * the resolved schema without bumping {@code schemaVersion}. Correctness
 * therefore depends on every write path that can change one of those rows
 * calling {@link SchemaCacheService#evictTenant(UUID)} - see the call sites
 * in {@code MetadataFieldOptionServiceImpl}, {@code MetadataFieldValidationServiceImpl},
 * {@code MetadataFieldServiceImpl}, and {@code DocumentTypeFieldServiceImpl#replaceMapping}.
 * The cache's TTL is only a backstop for a missed or failed eviction, not
 * the primary invalidation mechanism.
 */
@Service
@RequiredArgsConstructor
public class MetadataValidationServiceImpl implements MetadataValidationService {

    private final DocumentTypeRepository documentTypeRepository;
    private final DocumentTypeFieldRepository documentTypeFieldRepository;
    private final MetadataFieldRepository metadataFieldRepository;
    private final MetadataFieldOptionRepository metadataFieldOptionRepository;
    private final MetadataFieldValidationRepository metadataFieldValidationRepository;
    private final SchemaCacheService schemaCacheService;

    @Override
    @Transactional(readOnly = true)
    public List<ValidationError> validate(UUID documentTypeId, Integer schemaVersion, Map<String, Object> fieldValuesByKey) {
        UUID tenantId = TenantContext.getTenantId();
        int version = schemaVersion != null ? schemaVersion : currentVersion(documentTypeId, tenantId);

        @SuppressWarnings("unchecked")
        List<ResolvedField> fields = (List<ResolvedField>) schemaCacheService.get(tenantId, documentTypeId, version)
                .orElseGet(() -> {
                    List<ResolvedField> resolved = resolveSchema(documentTypeId, tenantId, version);
                    schemaCacheService.put(tenantId, documentTypeId, version, resolved);
                    return resolved;
                });

        List<ValidationError> errors = new ArrayList<>();
        for (ResolvedField field : fields) {
            if (field.hidden() || field.status() != MetadataField.FieldStatus.active) {
                continue;
            }

            Object value = fieldValuesByKey.get(field.fieldKey());
            boolean present = value != null && !(value instanceof String s && s.isBlank());

            // MDTM alternate flow 1: a mandatory dropdown/multi_select field
            // with no active options blocks submission with a distinct code,
            // regardless of what (if anything) was submitted for it.
            if (field.mandatory() && MetadataField.OPTION_BACKED_DATA_TYPES.contains(field.dataType())
                    && field.activeOptions().isEmpty()) {
                errors.add(new ValidationError(field.fieldKey(), "NO_ACTIVE_OPTIONS",
                        "Field '" + field.label() + "' has no active options configured. Contact an administrator.",
                        "error"));
                continue;
            }

            if (field.mandatory() && !present) {
                errors.add(new ValidationError(field.fieldKey(), "MANDATORY_FIELD_MISSING",
                        "Field '" + field.label() + "' is required.", "error"));
                continue;
            }

            if (present) {
                if (MetadataField.OPTION_BACKED_DATA_TYPES.contains(field.dataType()) && !field.activeOptions().isEmpty()) {
                    ValidationError optionError = checkOptionMembership(field, value);
                    if (optionError != null) {
                        errors.add(optionError);
                    }
                }
                for (CachedRule rule : field.rules()) {
                    ValidationError error = evaluateRule(field, rule, value);
                    if (error != null) {
                        errors.add(error);
                    }
                }
            }
        }
        return errors;
    }

    private int currentVersion(UUID documentTypeId, UUID tenantId) {
        return documentTypeRepository.findByDocumentTypeIdAndTenantIdAndDeletedFalse(documentTypeId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("DocumentType", documentTypeId))
                .getCurrentSchemaVersion();
    }

    private List<ResolvedField> resolveSchema(UUID documentTypeId, UUID tenantId, int version) {
        List<DocumentTypeField> mapping = documentTypeFieldRepository.findByDocumentTypeAndVersion(documentTypeId, tenantId, version);
        List<UUID> fieldIds = mapping.stream().map(DocumentTypeField::getFieldId).distinct().toList();
        if (fieldIds.isEmpty()) {
            return List.of();
        }

        Map<UUID, MetadataField> fieldsById = metadataFieldRepository
                .findAllByFieldIdInAndTenantIdAndDeletedFalse(fieldIds, tenantId).stream()
                .collect(Collectors.toMap(MetadataField::getFieldId, f -> f));

        Map<UUID, List<MetadataFieldValidation>> rulesByField = metadataFieldValidationRepository
                .findAllByFieldIn(fieldIds, tenantId).stream()
                .collect(Collectors.groupingBy(MetadataFieldValidation::getFieldId));

        Map<UUID, List<CachedOption>> optionsByField = metadataFieldOptionRepository
                .findAllActiveByFieldsOrdered(fieldIds, tenantId).stream()
                .collect(Collectors.groupingBy(MetadataFieldOption::getFieldId,
                        Collectors.mapping(o -> new CachedOption(o.getOptionId(), o.getOptionValue(), o.getOptionLabel(), o.getDisplayOrder()),
                                Collectors.toList())));

        List<ResolvedField> resolved = new ArrayList<>();
        for (DocumentTypeField m : mapping) {
            MetadataField field = fieldsById.get(m.getFieldId());
            if (field == null) {
                continue;
            }
            List<CachedOption> activeOptions = MetadataField.OPTION_BACKED_DATA_TYPES.contains(field.getDataType())
                    ? optionsByField.getOrDefault(field.getFieldId(), List.of())
                    : List.of();
            List<CachedRule> rules = rulesByField.getOrDefault(field.getFieldId(), List.of()).stream()
                    .map(r -> new CachedRule(r.getValidationId(), r.getRuleType(), r.getRuleValue(), r.getErrorMessage(), r.getSeverity()))
                    .toList();
            resolved.add(new ResolvedField(field.getFieldKey(), field.getLabel(), field.getDataType(),
                    m.isMandatory(), m.isHidden(), field.getStatus(), activeOptions, rules));
        }
        return resolved;
    }

    private List<Object> elementsOf(Object value) {
        return value instanceof Collection<?> collection ? List.copyOf(collection) : List.of(value);
    }

    private ValidationError evaluateRule(ResolvedField field, CachedRule rule, Object value) {
        for (Object element : elementsOf(value)) {
            ValidationError error = evaluateSingleValue(field, rule, element);
            if (error != null) {
                return error;
            }
        }
        return null;
    }

    private ValidationError checkOptionMembership(ResolvedField field, Object value) {
        Set<String> activeValues = field.activeOptions().stream()
                .map(CachedOption::optionValue)
                .collect(Collectors.toSet());
        for (Object element : elementsOf(value)) {
            if (!activeValues.contains(String.valueOf(element))) {
                return new ValidationError(field.fieldKey(), "VALUE_NOT_IN_OPTIONS",
                        "Field '" + field.label() + "' must be one of its active options.", "error");
            }
        }
        return null;
    }

    private ValidationError evaluateSingleValue(ResolvedField field, CachedRule rule, Object value) {
        String text = String.valueOf(value);
        String severity = rule.severity().name();
        try {
            switch (rule.ruleType()) {
                case min_length -> {
                    int min = Integer.parseInt(rule.ruleValue());
                    if (text.length() < min) {
                        return error(field, rule, "MIN_LENGTH", severity, "be at least " + min + " characters");
                    }
                }
                case max_length -> {
                    int max = Integer.parseInt(rule.ruleValue());
                    if (text.length() > max) {
                        return error(field, rule, "MAX_LENGTH", severity, "be at most " + max + " characters");
                    }
                }
                case min_value -> {
                    double min = Double.parseDouble(rule.ruleValue());
                    if (Double.parseDouble(text) < min) {
                        return error(field, rule, "MIN_VALUE", severity, "be at least " + min);
                    }
                }
                case max_value -> {
                    double max = Double.parseDouble(rule.ruleValue());
                    if (Double.parseDouble(text) > max) {
                        return error(field, rule, "MAX_VALUE", severity, "be at most " + max);
                    }
                }
                case regex -> {
                    if (!Pattern.matches(rule.ruleValue(), text)) {
                        return error(field, rule, "REGEX", severity, "match the required format");
                    }
                }
                case date_format -> {
                    try {
                        DateTimeFormatter.ofPattern(rule.ruleValue()).parse(text);
                    } catch (DateTimeParseException e) {
                        return error(field, rule, "DATE_FORMAT", severity, "match the format '" + rule.ruleValue() + "'");
                    }
                }
                case date_range -> {
                    String[] parts = rule.ruleValue().split("/", 2);
                    LocalDate min = LocalDate.parse(parts[0]);
                    LocalDate max = LocalDate.parse(parts[1]);
                    LocalDate actual = LocalDate.parse(text);
                    if (actual.isBefore(min) || actual.isAfter(max)) {
                        return error(field, rule, "DATE_RANGE", severity, "be between " + parts[0] + " and " + parts[1]);
                    }
                }
                case allowed_values -> {
                    Set<String> allowed = Arrays.stream(rule.ruleValue().split(",")).map(String::trim)
                            .collect(Collectors.toSet());
                    if (!allowed.contains(text)) {
                        return error(field, rule, "ALLOWED_VALUES", severity, "be one of: " + rule.ruleValue());
                    }
                }
            }
        } catch (RuntimeException e) {
            // Malformed input relative to the rule (e.g. non-numeric text
            // against min_value, an unparsable date) - a validation failure
            // in its own right, not a server error.
            return error(field, rule, rule.ruleType().name().toUpperCase(), severity, "be valid");
        }
        return null;
    }

    private ValidationError error(ResolvedField field, CachedRule rule, String code, String severity,
                                   String requirement) {
        String message = rule.errorMessage() != null
                ? rule.errorMessage()
                : "Field '" + field.label() + "' must " + requirement + ".";
        return new ValidationError(field.fieldKey(), code, message, severity);
    }

    /**
     * Plain, cache-safe projection of {@link MetadataFieldOption} - not the
     * entity itself, which failed to serialize through the schema cache's
     * Jackson mapper by default ({@code OffsetDateTime} fields like
     * {@code createdAt} need the JSR-310 module, and an entity carries
     * columns this cache has no use for anyway).
     */
    private record CachedOption(UUID optionId, String optionValue, String optionLabel, int displayOrder) {
    }

    /** Plain, cache-safe projection of {@link MetadataFieldValidation} - see {@link CachedOption}. */
    private record CachedRule(UUID validationId, MetadataFieldValidation.RuleType ruleType, String ruleValue,
                               String errorMessage, MetadataFieldValidation.Severity severity) {
    }

    private record ResolvedField(String fieldKey, String label, String dataType, boolean mandatory, boolean hidden,
                                  MetadataField.FieldStatus status, List<CachedOption> activeOptions, List<CachedRule> rules) {
    }
}
