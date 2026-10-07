package com.oak.dms.importexport.validation;

import com.oak.dms.metadata.dto.ValidationError;
import com.oak.dms.metadata.entity.MetadataFieldOption;
import com.oak.dms.metadata.repository.MetadataFieldOptionRepository;
import com.oak.dms.metadata.service.MetadataValidationService;
import com.oak.dms.user.User;
import com.oak.dms.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ImportDefaultsTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID DOCUMENT_TYPE_ID = UUID.randomUUID();
    private static final int SCHEMA_VERSION = 2;

    private final UserRepository userRepository = mock(UserRepository.class);
    private final MetadataValidationService validationService = mock(MetadataValidationService.class);
    private final MetadataFieldOptionRepository optionRepository = mock(MetadataFieldOptionRepository.class);
    private final ImportDefaults defaults = new ImportDefaults(new ImportValueConverter(),
            new ImportUsers(userRepository), validationService, optionRepository);

    private final List<MetadataFieldOption> options = new ArrayList<>();

    @BeforeEach
    void setUp() {
        when(optionRepository.findAllActiveByFieldsOrdered(anyList(), eq(TENANT_ID))).thenReturn(options);
        when(validationService.validate(eq(DOCUMENT_TYPE_ID), eq(SCHEMA_VERSION), anyMap())).thenReturn(List.of());
    }

    @Test
    void resolve_convertsEachDefaultForItsDataType() {
        UUID ownerId = UUID.randomUUID();
        when(userRepository.findByTenantIdAndEmailAndDeletedFalse(TENANT_ID, "jane@oak.lk"))
                .thenReturn(Optional.of(User.builder().userId(ownerId).tenantId(TENANT_ID)
                        .status(User.UserStatus.active).build()));
        Map<String, ImportField> fields = fields(
                field("amount", "Amount", "decimal", false, " 12.50 "),
                field("count", "Count", "number", true, "3"),
                field("owner", "Owner", "user_ref", false, "jane@oak.lk"),
                field("note", "Note", "text", false, null));

        ImportDefaults.Resolved resolved = resolve(fields);

        assertThat(resolved.applied())
                .extracting(applied -> applied.field().fieldKey(), ImportDefaults.AppliedDefault::rawValue,
                        ImportDefaults.AppliedDefault::value)
                .containsExactly(
                        tuple("amount", "12.50", new BigDecimal("12.50")),
                        tuple("count", "3", 3L),
                        tuple("owner", "jane@oak.lk", ownerId.toString()));
        assertThat(resolved.invalid()).isEmpty();
        assertThat(resolved.missingMandatory()).isEmpty();
    }

    @Test
    void resolve_defaultThatDoesNotConvert_isSkippedWithAWarning() {
        Map<String, ImportField> fields = fields(field("count", "Count", "number", false, "three"));

        ImportDefaults.Resolved resolved = resolve(fields);

        assertThat(resolved.applied()).isEmpty();
        assertThat(resolved.invalid()).extracting(ImportRowError::field, ImportRowError::code,
                        ImportRowError::severity)
                .containsExactly(tuple("count", "DEFAULT_VALUE_INVALID", ImportRowError.WARNING));
        assertThat(resolved.invalid().get(0).message())
                .isEqualTo("The default value for 'Count' couldn't be used: Count must be a whole number - it was"
                        + " left empty");
        assertThat(resolved.invalidDefaults()).extracting(ImportDefaults.InvalidDefault::rawValue)
                .containsExactly("three");
        assertThat(resolved.missingMandatory()).isEmpty();
    }

    @Test
    void resolve_mandatoryFieldsWithNoOrAnUnusableDefault_areMissing() {
        // No such active user, so the user_ref default can't be used.
        ImportField owner = field("owner", "Owner", "user_ref", true, "gone@oak.lk");
        ImportField reference = field("reference", "Reference", "text", true, null);
        ImportField title = field("doc_title", "Doc Title", "text", true, "Untitled");

        ImportDefaults.Resolved resolved = resolve(fields(owner, reference, title));

        assertThat(resolved.missingMandatory()).containsExactly(owner, reference);
        assertThat(resolved.applied()).extracting(applied -> applied.field().fieldKey()).containsExactly("doc_title");
        assertThat(resolved.invalid()).extracting(ImportRowError::field).containsExactly("owner");
    }

    @Test
    void resolve_defaultTheValidatorRejects_isDroppedWithItsMessage() {
        ImportField reference = field("reference", "Reference", "text", false, "R-1");
        ImportField region = field("region", "Region", "text", false, "EMEA");
        when(validationService.validate(eq(DOCUMENT_TYPE_ID), eq(SCHEMA_VERSION), anyMap())).thenReturn(List.of(
                new ValidationError("reference", "MIN_LENGTH", "Field 'Reference' must be at least 5 characters.",
                        "error")));

        ImportDefaults.Resolved resolved = resolve(fields(reference, region));

        assertThat(resolved.applied()).extracting(applied -> applied.field().fieldKey()).containsExactly("region");
        assertThat(resolved.invalid()).containsExactly(new ImportRowError("reference", "DEFAULT_VALUE_INVALID",
                "The default value for 'Reference' couldn't be used: Field 'Reference' must be at least 5"
                        + " characters - it was left empty", ImportRowError.WARNING));
    }

    @Test
    void resolve_validatesTheConvertedDefaultsAgainstTheBatchVersion() {
        option("retention", "long_term", "Long-Term (8-15 years)");
        ImportField retention = field("retention", "Retention Class", "dropdown", false, "Long-term");
        ImportField count = field("count", "Count", "number", false, "3");

        resolve(fields(retention, count));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> values = ArgumentCaptor.forClass(Map.class);
        verify(validationService).validate(eq(DOCUMENT_TYPE_ID), eq(SCHEMA_VERSION), values.capture());
        assertThat(values.getValue()).containsExactlyInAnyOrderEntriesOf(Map.of("retention", "long_term",
                "count", 3L));
    }

    @Test
    void resolve_mandatoryFieldWhoseDefaultTheValidatorRejects_isMissing() {
        ImportField reference = field("reference", "Reference", "text", true, "R-1");
        when(validationService.validate(eq(DOCUMENT_TYPE_ID), eq(SCHEMA_VERSION), anyMap())).thenReturn(List.of(
                new ValidationError("reference", "REGEX", "Field 'Reference' must match the required format.",
                        "error")));

        ImportDefaults.Resolved resolved = resolve(fields(reference));

        assertThat(resolved.applied()).isEmpty();
        assertThat(resolved.invalid()).extracting(ImportRowError::field).containsExactly("reference");
        assertThat(resolved.missingMandatory()).containsExactly(reference);
    }

    @Test
    void resolve_ignoresTheValidatorsMandatoryErrorsAndWarnings() {
        ImportField reference = field("reference", "Reference", "text", false, "R-1");
        ImportField notes = field("notes", "Notes", "text", true, null);
        when(validationService.validate(eq(DOCUMENT_TYPE_ID), eq(SCHEMA_VERSION), anyMap())).thenReturn(List.of(
                new ValidationError("notes", "MANDATORY_FIELD_MISSING", "Field 'Notes' is required.", "error"),
                new ValidationError("reference", "MAX_LENGTH", "Field 'Reference' is a bit long.", "warning")));

        ImportDefaults.Resolved resolved = resolve(fields(reference, notes));

        assertThat(resolved.applied()).extracting(applied -> applied.field().fieldKey())
                .containsExactly("reference");
        assertThat(resolved.invalid()).isEmpty();
        // Still missing - but because it has no default, not from the validator.
        assertThat(resolved.missingMandatory()).containsExactly(notes);
    }

    @Test
    void resolve_noUsableDefaults_doesNotCallTheValidator() {
        resolve(fields(field("notes", "Notes", "text", true, null)));

        verify(validationService, never()).validate(any(), any(), anyMap());
    }

    @Test
    void resolve_dropdownDefaultMatchingAnOptionLabel_isStoredAsItsCode() {
        option("classification", "internal", "Internal");
        option("classification", "public", "Public");

        ImportDefaults.Resolved resolved = resolve(fields(
                field("classification", "Classification", "dropdown", false, "Internal")));

        assertThat(resolved.applied()).extracting(ImportDefaults.AppliedDefault::rawValue,
                ImportDefaults.AppliedDefault::value).containsExactly(tuple("Internal", "internal"));
        assertThat(resolved.invalid()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Long-term", "long term", "LONG_TERM", " long_term ", "Long-Term (8-15 years)"})
    void resolve_dropdownDefaultMatchingAnOptionCodeOrLabel_isStoredAsLongTerm(String defaultValue) {
        option("retention", "short_term", "Short-Term (under 8 years)");
        option("retention", "long_term", "Long-Term (8-15 years)");

        ImportDefaults.Resolved resolved = resolve(fields(
                field("retention", "Retention Class", "dropdown", false, defaultValue)));

        assertThat(resolved.applied()).extracting(ImportDefaults.AppliedDefault::value).containsExactly("long_term");
        assertThat(resolved.invalid()).isEmpty();
    }

    @Test
    void resolve_dropdownDefaultMatchingNothing_isDroppedAndNeverValidated() {
        option("retention", "long_term", "Long-Term (8-15 years)");
        ImportField retention = field("retention", "Retention Class", "dropdown", true, "long");

        ImportDefaults.Resolved resolved = resolve(fields(retention));

        assertThat(resolved.applied()).isEmpty();
        assertThat(resolved.invalid()).containsExactly(new ImportRowError("retention", "DEFAULT_VALUE_INVALID",
                "The default value for 'Retention Class' couldn't be used: 'long' isn't one of the field's active"
                        + " options - it was left empty", ImportRowError.WARNING));
        assertThat(resolved.invalidDefaults()).extracting(ImportDefaults.InvalidDefault::rawValue)
                .containsExactly("long");
        assertThat(resolved.missingMandatory()).containsExactly(retention);
        verify(validationService, never()).validate(any(), any(), anyMap());
    }

    @Test
    void resolve_dropdownDefaultMatchingMoreThanOneOption_isDropped() {
        // "Archive" is one option's code and another's label.
        option("disposition", "archive", "Keep");
        option("disposition", "destroy", "Archive");

        ImportDefaults.Resolved resolved = resolve(fields(
                field("disposition", "Disposition", "dropdown", false, "Archive")));

        assertThat(resolved.applied()).isEmpty();
        assertThat(resolved.invalid()).extracting(ImportRowError::message).containsExactly(
                "The default value for 'Disposition' couldn't be used: 'Archive' matches more than one option"
                        + " - it was left empty");
    }

    @Test
    void resolve_multiSelectDefault_matchesEachItem() {
        option("tags", "internal", "Internal");
        option("tags", "long_term", "Long-Term (8-15 years)");
        option("tags", "legal", "Legal");

        ImportDefaults.Resolved resolved = resolve(fields(
                field("tags", "Tags", "multi_select", false, "Internal; long term")));

        assertThat(resolved.applied()).extracting(ImportDefaults.AppliedDefault::value)
                .containsExactly(List.of("internal", "long_term"));
    }

    @Test
    void resolve_multiSelectDefaultWithAnUnmatchedItem_isDroppedWhole() {
        option("tags", "internal", "Internal");

        ImportDefaults.Resolved resolved = resolve(fields(
                field("tags", "Tags", "multi_select", false, "Internal; Secret")));

        assertThat(resolved.applied()).isEmpty();
        assertThat(resolved.invalidDefaults()).extracting(ImportDefaults.InvalidDefault::reason)
                .containsExactly("'Secret' isn't one of the field's active options");
    }

    @Test
    void resolve_hiddenDropdownWithAnUnmatchedDefault_isDropped() {
        // The metadata validator skips hidden fields, so the option check here is what catches it.
        option("retention", "long_term", "Long-Term (8-15 years)");
        ImportField retention = new ImportField(fieldId("retention"), "retention", "Retention Class", "dropdown",
                false, true, "Forever");

        ImportDefaults.Resolved resolved = resolve(fields(retention));

        assertThat(resolved.applied()).isEmpty();
        assertThat(resolved.invalid()).extracting(ImportRowError::field).containsExactly("retention");
    }

    @Test
    void resolve_hiddenActiveField_getsItsDefault() {
        ImportField source = new ImportField(UUID.randomUUID(), "source", "Source", "text", false, true,
                "Bulk import");

        ImportDefaults.Resolved resolved = resolve(fields(source));

        assertThat(resolved.applied()).extracting(ImportDefaults.AppliedDefault::value)
                .containsExactly("Bulk import");
    }

    @Test
    void resolve_inactiveField_isSkippedSilently() {
        ImportField retired = new ImportField(UUID.randomUUID(), "legacy_code", "Legacy Code", "dropdown", true,
                false, "nonsense", false);
        ImportField retiredWithoutDefault = new ImportField(UUID.randomUUID(), "old_ref", "Old Ref", "text", true,
                false, null, false);

        ImportDefaults.Resolved resolved = resolve(fields(retired, retiredWithoutDefault));

        assertThat(resolved.applied()).isEmpty();
        assertThat(resolved.invalidDefaults()).isEmpty();
        assertThat(resolved.missingMandatory()).isEmpty();
        verify(validationService, never()).validate(any(), any(), anyMap());
    }

    @Test
    void labels_joinsWithAnd() {
        ImportField a = field("a", "A", "text", true, null);
        ImportField b = field("b", "B", "text", true, null);
        ImportField c = field("c", null, "text", true, null);

        assertThat(ImportDefaults.labels(List.of(a))).isEqualTo("A");
        assertThat(ImportDefaults.labels(List.of(a, b))).isEqualTo("A and B");
        assertThat(ImportDefaults.labels(List.of(a, b, c))).isEqualTo("A, B and c");
    }

    private ImportDefaults.Resolved resolve(Map<String, ImportField> fields) {
        return defaults.resolve(fields, TENANT_ID, DOCUMENT_TYPE_ID, SCHEMA_VERSION);
    }

    /** Field ids are derived from keys, so options can be added before their field exists. */
    private void option(String fieldKey, String code, String label) {
        options.add(MetadataFieldOption.builder().fieldId(fieldId(fieldKey)).optionValue(code).optionLabel(label)
                .build());
    }

    private static UUID fieldId(String fieldKey) {
        return UUID.nameUUIDFromBytes(fieldKey.getBytes());
    }

    private static ImportField field(String key, String label, String dataType, boolean mandatory,
                                     String defaultValue) {
        return new ImportField(fieldId(key), key, label, dataType, mandatory, false, defaultValue);
    }

    private static Map<String, ImportField> fields(ImportField... fields) {
        Map<String, ImportField> map = new LinkedHashMap<>();
        for (ImportField field : fields) {
            map.put(field.fieldKey(), field);
        }
        return map;
    }
}
