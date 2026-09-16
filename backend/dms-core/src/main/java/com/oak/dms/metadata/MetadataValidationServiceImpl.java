package com.oak.dms.metadata;

import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.metadata.dto.ValidationError;
import com.oak.dms.security.auth.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * See {@link MetadataValidationService}.
 * <p>
 * MDTM-NFR-002 (validation completes without noticeable delay) is met with a
 * simple in-memory {@link ConcurrentHashMap} cache keyed by
 * {@code (tenantId, documentTypeId, version)} rather than Spring's cache
 * abstraction - this backend has no {@code spring-boot-starter-cache}/
 * {@code @EnableCaching} configured anywhere, and one wasn't worth adding for
 * this alone. Caching per specific version is safe indefinitely: once a
 * version is published, its {@code document_type_fields} rows are either the
 * current active set or archived - never edited in place (see
 * {@code DocumentTypeFieldServiceImpl#replaceMapping}) - so a cached
 * version's resolved schema can never go stale. Only the *current* version
 * number changes over time, and that's a cheap uncached column read on every
 * call, not the expensive part.
 */
@Service
@RequiredArgsConstructor
public class MetadataValidationServiceImpl implements MetadataValidationService {

    private final DocumentTypeRepository documentTypeRepository;
    private final DocumentTypeFieldRepository documentTypeFieldRepository;
    private final MetadataFieldRepository metadataFieldRepository;
    private final MetadataFieldOptionRepository metadataFieldOptionRepository;
    private final MetadataFieldValidationRepository metadataFieldValidationRepository;

    private final Map<CacheKey, List<ResolvedField>> schemaCache = new ConcurrentHashMap<>();

    @Override
    @Transactional(readOnly = true)
    public List<ValidationError> validate(UUID documentTypeId, Integer schemaVersion, Map<String, Object> fieldValuesByKey) {
        UUID tenantId = TenantContext.getTenantId();
        int version = schemaVersion != null ? schemaVersion : currentVersion(documentTypeId, tenantId);
        CacheKey key = new CacheKey(tenantId, documentTypeId, version);
        List<ResolvedField> fields = schemaCache.computeIfAbsent(key, k -> resolveSchema(documentTypeId, tenantId, version));

        List<ValidationError> errors = new ArrayList<>();
        for (ResolvedField field : fields) {
            if (field.hidden()) {
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
                for (MetadataFieldValidation rule : field.rules()) {
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

        Map<UUID, MetadataField> fieldsById = metadataFieldRepository
                .findAllByFieldIdInAndTenantIdAndDeletedFalse(fieldIds, tenantId).stream()
                .collect(Collectors.toMap(MetadataField::getFieldId, f -> f));

        Map<UUID, List<MetadataFieldValidation>> rulesByField = metadataFieldValidationRepository
                .findAllByFieldIn(fieldIds, tenantId).stream()
                .collect(Collectors.groupingBy(MetadataFieldValidation::getFieldId));

        List<ResolvedField> resolved = new ArrayList<>();
        for (DocumentTypeField m : mapping) {
            MetadataField field = fieldsById.get(m.getFieldId());
            if (field == null) {
                continue;
            }
            List<MetadataFieldOption> activeOptions = MetadataField.OPTION_BACKED_DATA_TYPES.contains(field.getDataType())
                    ? metadataFieldOptionRepository.findAllActiveByFieldOrdered(field.getFieldId(), tenantId)
                    : List.of();
            resolved.add(new ResolvedField(field.getFieldKey(), field.getLabel(), field.getDataType(),
                    m.isMandatory(), m.isHidden(), activeOptions, rulesByField.getOrDefault(field.getFieldId(), List.of())));
        }
        return resolved;
    }

    private ValidationError evaluateRule(ResolvedField field, MetadataFieldValidation rule, Object value) {
        String text = String.valueOf(value);
        String severity = rule.getSeverity().name();
        try {
            switch (rule.getRuleType()) {
                case min_length -> {
                    int min = Integer.parseInt(rule.getRuleValue());
                    if (text.length() < min) {
                        return error(field, rule, "MIN_LENGTH", severity, "be at least " + min + " characters");
                    }
                }
                case max_length -> {
                    int max = Integer.parseInt(rule.getRuleValue());
                    if (text.length() > max) {
                        return error(field, rule, "MAX_LENGTH", severity, "be at most " + max + " characters");
                    }
                }
                case min_value -> {
                    double min = Double.parseDouble(rule.getRuleValue());
                    if (Double.parseDouble(text) < min) {
                        return error(field, rule, "MIN_VALUE", severity, "be at least " + min);
                    }
                }
                case max_value -> {
                    double max = Double.parseDouble(rule.getRuleValue());
                    if (Double.parseDouble(text) > max) {
                        return error(field, rule, "MAX_VALUE", severity, "be at most " + max);
                    }
                }
                case regex -> {
                    if (!Pattern.matches(rule.getRuleValue(), text)) {
                        return error(field, rule, "REGEX", severity, "match the required format");
                    }
                }
                case date_format -> DateTimeFormatter.ofPattern(rule.getRuleValue()).parse(text);
                case date_range -> {
                    String[] parts = rule.getRuleValue().split("/", 2);
                    LocalDate min = LocalDate.parse(parts[0]);
                    LocalDate max = LocalDate.parse(parts[1]);
                    LocalDate actual = LocalDate.parse(text);
                    if (actual.isBefore(min) || actual.isAfter(max)) {
                        return error(field, rule, "DATE_RANGE", severity, "be between " + parts[0] + " and " + parts[1]);
                    }
                }
                case allowed_values -> {
                    Set<String> allowed = Arrays.stream(rule.getRuleValue().split(",")).map(String::trim)
                            .collect(Collectors.toSet());
                    if (!allowed.contains(text)) {
                        return error(field, rule, "ALLOWED_VALUES", severity, "be one of: " + rule.getRuleValue());
                    }
                }
            }
        } catch (RuntimeException e) {
            // Malformed input relative to the rule (e.g. non-numeric text
            // against min_value, an unparsable date) - a validation failure
            // in its own right, not a server error.
            return error(field, rule, rule.getRuleType().name().toUpperCase(), severity, "be valid");
        }
        return null;
    }

    private ValidationError error(ResolvedField field, MetadataFieldValidation rule, String code, String severity,
                                   String requirement) {
        String message = rule.getErrorMessage() != null
                ? rule.getErrorMessage()
                : "Field '" + field.label() + "' must " + requirement + ".";
        return new ValidationError(field.fieldKey(), code, message, severity);
    }

    private record CacheKey(UUID tenantId, UUID documentTypeId, int version) {
    }

    private record ResolvedField(String fieldKey, String label, String dataType, boolean mandatory, boolean hidden,
                                  List<MetadataFieldOption> activeOptions, List<MetadataFieldValidation> rules) {
    }
}
