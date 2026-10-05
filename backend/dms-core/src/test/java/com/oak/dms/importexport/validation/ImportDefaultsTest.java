package com.oak.dms.importexport.validation;

import com.oak.dms.user.User;
import com.oak.dms.user.UserRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ImportDefaultsTest {

    private static final UUID TENANT_ID = UUID.randomUUID();

    private final UserRepository userRepository = mock(UserRepository.class);
    private final ImportDefaults defaults = new ImportDefaults(new ImportValueConverter(),
            new ImportUsers(userRepository));

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

        ImportDefaults.Resolved resolved = defaults.resolve(fields, TENANT_ID);

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

        ImportDefaults.Resolved resolved = defaults.resolve(fields, TENANT_ID);

        assertThat(resolved.applied()).isEmpty();
        assertThat(resolved.invalid()).extracting(ImportRowError::field, ImportRowError::code,
                        ImportRowError::severity)
                .containsExactly(tuple("count", "DEFAULT_VALUE_INVALID", ImportRowError.WARNING));
        assertThat(resolved.invalid().get(0).message())
                .isEqualTo("The default value for Count couldn't be used (Count must be a whole number) - it was"
                        + " left empty");
        assertThat(resolved.missingMandatory()).isEmpty();
    }

    @Test
    void resolve_mandatoryFieldsWithNoOrAnUnusableDefault_areMissing() {
        // No such active user, so the user_ref default can't be used.
        ImportField owner = field("owner", "Owner", "user_ref", true, "gone@oak.lk");
        ImportField reference = field("reference", "Reference", "text", true, null);
        ImportField title = field("doc_title", "Doc Title", "text", true, "Untitled");

        ImportDefaults.Resolved resolved = defaults.resolve(fields(owner, reference, title), TENANT_ID);

        assertThat(resolved.missingMandatory()).containsExactly(owner, reference);
        assertThat(resolved.applied()).extracting(applied -> applied.field().fieldKey()).containsExactly("doc_title");
        assertThat(resolved.invalid()).extracting(ImportRowError::field).containsExactly("owner");
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

    private static ImportField field(String key, String label, String dataType, boolean mandatory,
                                     String defaultValue) {
        return new ImportField(UUID.randomUUID(), key, label, dataType, mandatory, false, defaultValue);
    }

    private static Map<String, ImportField> fields(ImportField... fields) {
        Map<String, ImportField> map = new LinkedHashMap<>();
        for (ImportField field : fields) {
            map.put(field.fieldKey(), field);
        }
        return map;
    }
}
