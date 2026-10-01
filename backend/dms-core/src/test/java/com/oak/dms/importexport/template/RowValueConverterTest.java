package com.oak.dms.importexport.template;

import com.oak.dms.metadata.dto.DocumentTypeSchemaResponse.SchemaField;
import com.oak.dms.user.User;
import com.oak.dms.user.UserRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.oak.dms.importexport.template.ImportTemplateRoundTripTest.field;
import static com.oak.dms.importexport.template.ImportTemplateRoundTripTest.option;
import static com.oak.dms.importexport.template.ImportTemplateRoundTripTest.rule;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RowValueConverterTest {

    private static final UUID TENANT = UUID.randomUUID();

    private final UserRepository users = mock(UserRepository.class);
    private final RowValueConverter converter = new RowValueConverter(users);

    @Test
    void convertsEachDataType_toWhatDocumentMetadataStores() {
        UUID choiceId = UUID.randomUUID();
        UUID tagsId = UUID.randomUUID();
        List<SchemaField> fields = List.of(
                field("count", "number", false, false, "active", List.of(), List.of()),
                field("amount", "decimal", false, false, "active", List.of(), List.of()),
                field("issued_on", "date", false, false, "active", List.of(), List.of()),
                field("seen_at", "datetime", false, false, "active", List.of(), List.of()),
                field("paid", "boolean", false, false, "active", List.of(), List.of()),
                new SchemaField(choiceId, "state", "State", null, "dropdown", "active", false, false, 0, null, null,
                        List.of(option(choiceId, "open", "Open")), List.of()),
                new SchemaField(tagsId, "tags", "Tags", null, "multi_select", "active", false, false, 0, null, null,
                        List.of(option(tagsId, "a", "Alpha"), option(tagsId, "b", "Beta")), List.of()),
                field("note", "text", false, false, "active", List.of(), List.of()));

        RowValueConverter.Converted result = converter.convert(fields, Map.of(
                "count", "42",
                "amount", "12.50",
                "issued_on", "2026-09-30",
                "seen_at", "2026-09-30 08:15",
                "paid", "yes",
                "state", "OPEN",
                "tags", "Beta | a | b",
                "note", " hello "), TENANT);

        assertThat(result.errors()).isEmpty();
        assertThat(result.stored()).containsEntry("count", 42L)
                .containsEntry("amount", new BigDecimal("12.50"))
                .containsEntry("issued_on", "2026-09-30")
                .containsEntry("seen_at", "2026-09-30T08:15Z")
                .containsEntry("paid", true)
                .containsEntry("state", "open")        // label/case matched, value stored
                .containsEntry("tags", List.of("b", "a"))
                .containsEntry("note", "hello");
        // Dates are validated as typed (a date_format rule checks the text).
        assertThat(result.forValidation()).containsEntry("seen_at", "2026-09-30 08:15");
    }

    @Test
    void wrongTypes_areRowErrorsNamingTheColumn() {
        UUID choiceId = UUID.randomUUID();
        List<SchemaField> fields = List.of(
                field("count", "number", false, false, "active", List.of(), List.of()),
                field("amount", "decimal", false, false, "active", List.of(), List.of()),
                field("issued_on", "date", false, false, "active", List.of(), List.of()),
                field("paid", "boolean", false, false, "active", List.of(), List.of()),
                new SchemaField(choiceId, "state", "State", null, "dropdown", "active", false, false, 0, null, null,
                        List.of(option(choiceId, "open", "Open")), List.of()));

        RowValueConverter.Converted result = converter.convert(fields, Map.of(
                "count", "4.5", "amount", "12,50", "issued_on", "30/09/2026", "paid", "maybe", "state", "shut"), TENANT);

        assertThat(result.errors()).containsExactlyInAnyOrder(
                "count: \"4.5\" is not a whole number",
                "amount: \"12,50\" is not a number (use . as the decimal point)",
                "issued_on: \"30/09/2026\" is not a date as YYYY-MM-DD",
                "paid: \"maybe\" must be Yes or No",
                "state: \"shut\" is not one of the allowed values");
        assertThat(result.stored()).isEmpty();
    }

    @Test
    void date_followsTheFieldsDateFormatRule_butIsStoredAsIso() {
        List<SchemaField> fields = List.of(field("issued_on", "date", false, false, "active", List.of(),
                List.of(rule("date_format", "dd/MM/yyyy", "error"))));

        RowValueConverter.Converted result = converter.convert(fields, Map.of("issued_on", "30/09/2026"), TENANT);

        assertThat(result.errors()).isEmpty();
        assertThat(result.stored()).containsEntry("issued_on", "2026-09-30");
        assertThat(result.forValidation()).containsEntry("issued_on", "30/09/2026");
    }

    @Test
    void userRef_resolvesAnEmailOfThisTenantToTheUserId() {
        User user = User.builder().userId(UUID.randomUUID()).tenantId(TENANT).build();
        when(users.findByTenantIdAndEmailAndDeletedFalse(TENANT, "ana@example.com")).thenReturn(Optional.of(user));
        when(users.findByTenantIdAndEmailAndDeletedFalse(TENANT, "ghost@example.com")).thenReturn(Optional.empty());
        when(users.findByUserIdAndTenantIdAndDeletedFalse(any(), any())).thenReturn(Optional.empty());
        List<SchemaField> fields = List.of(field("owner", "user_ref", false, false, "active", List.of(), List.of()));

        assertThat(converter.convert(fields, Map.of("owner", "ana@example.com"), TENANT).stored())
                .containsEntry("owner", user.getUserId().toString());
        assertThat(converter.convert(fields, Map.of("owner", "ghost@example.com"), TENANT).errors())
                .containsExactly("owner: no user with email ghost@example.com");
        UUID unknown = UUID.randomUUID();
        assertThat(converter.convert(fields, Map.of("owner", unknown.toString()), TENANT).errors())
                .containsExactly("owner: no user with id " + unknown);
        assertThat(converter.convert(fields, Map.of("owner", "ana"), TENANT).errors())
                .containsExactly("owner: \"ana\" is not a user's email address");
    }

    @Test
    void blankCells_areLeftOut() {
        List<SchemaField> fields = List.of(field("count", "number", true, false, "active", List.of(), List.of()));

        RowValueConverter.Converted result = converter.convert(fields, Map.of("count", "  "), TENANT);

        assertThat(result.errors()).isEmpty();
        assertThat(result.stored()).isEmpty();
    }
}
