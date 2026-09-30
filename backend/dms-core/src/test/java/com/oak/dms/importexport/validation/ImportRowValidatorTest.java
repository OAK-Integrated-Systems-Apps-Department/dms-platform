package com.oak.dms.importexport.validation;

import com.oak.dms.importexport.entity.ImportItem;
import com.oak.dms.importexport.validation.ImportRowValidator.RowValues;
import com.oak.dms.metadata.dto.ValidationError;
import com.oak.dms.metadata.service.MetadataValidationService;
import com.oak.dms.user.User;
import com.oak.dms.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ImportRowValidatorTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID DOCUMENT_TYPE_ID = UUID.randomUUID();
    private static final int VERSION = 4;

    private static final Map<String, ImportField> FIELDS = fields(
            new ImportField("contract_value", "Contract Value", "number", true, false),
            new ImportField("start_date", "Start Date", "date", false, false),
            new ImportField("owner", "Owner", "user_ref", false, false));

    private final MetadataValidationService metadataValidationService = mock(MetadataValidationService.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final ImportRowValidator validator =
            new ImportRowValidator(new ImportValueConverter(), metadataValidationService, userRepository);

    @BeforeEach
    void noMetadataErrorsByDefault() {
        when(metadataValidationService.validate(any(), anyInt(), anyMap())).thenReturn(List.of());
    }

    @Test
    void validate_validRow_hasNoErrorsAndPassesConvertedValuesToMetadataValidation() {
        UUID ownerId = UUID.randomUUID();
        when(userRepository.findByTenantIdAndEmailAndDeletedFalse(TENANT_ID, "jane@oak.lk"))
                .thenReturn(Optional.of(user(ownerId, User.UserStatus.active)));

        List<ImportRowError> errors = validate(item(2, "contract.pdf", "key"),
                new RowValues("Contract", Map.of("contract_value", "1200", "start_date", "2026-09-30",
                        "owner", "jane@oak.lk", "not_in_schema", "ignored")));

        assertThat(errors).isEmpty();
        verify(metadataValidationService).validate(DOCUMENT_TYPE_ID, VERSION, Map.of(
                "contract_value", 1200L, "start_date", "2026-09-30", "owner", ownerId.toString()));
    }

    @Test
    void validate_noStagedFile_failsFileMissing() {
        List<ImportRowError> errors = validate(item(2, "contract.pdf", null), new RowValues(null, Map.of()));

        assertThat(errors).containsExactly(
                ImportRowError.error("file", "FILE_MISSING", "No file was uploaded for this row"));
        assertThat(ImportRowValidator.hasErrors(errors)).isTrue();
    }

    @Test
    void validate_blankFilename_failsFilenameMissing() {
        List<ImportRowError> errors = validate(item(2, "  ", "key"), new RowValues("A title", Map.of()));

        assertThat(errors).extracting(ImportRowError::code).containsExactly("FILENAME_MISSING");
    }

    @Test
    void validate_duplicateFilenames_failEveryMatchingRowListingAllRows() {
        List<ImportItem> items = List.of(
                item(3, "contract.pdf", "a"),
                item(4, "other.pdf", "b"),
                item(5, " CONTRACT.pdf ", "c"),
                item(7, "Contract.PDF", "d"),
                item(8, "", "e"),
                item(9, " ", "f"));
        Map<String, List<Integer>> duplicates = ImportRowValidator.duplicateRows(items);

        assertThat(duplicates).containsOnlyKeys("contract.pdf");
        for (int i : List.of(0, 2, 3)) {
            ImportItem item = items.get(i);
            List<ImportRowError> errors = validator.validate(item, new RowValues(null, Map.of()), FIELDS, duplicates,
                    DOCUMENT_TYPE_ID, VERSION, TENANT_ID);
            assertThat(errors).containsExactly(ImportRowError.error("filename", "DUPLICATE_FILENAME",
                    "'" + item.getFilenameReference().trim()
                            + "' appears on rows 3, 5 and 7 - each file needs a unique name"));
        }
        assertThat(validator.validate(items.get(1), new RowValues(null, Map.of()), FIELDS, duplicates,
                DOCUMENT_TYPE_ID, VERSION, TENANT_ID)).isEmpty();
    }

    @Test
    void duplicateRows_twoRows_joinedWithAnd() {
        Map<String, List<Integer>> duplicates = ImportRowValidator.duplicateRows(
                List.of(item(7, "contract.pdf", "a"), item(3, "contract.pdf", "b")));

        List<ImportRowError> errors = validator.validate(item(3, "contract.pdf", "b"), new RowValues(null, Map.of()),
                FIELDS, duplicates, DOCUMENT_TYPE_ID, VERSION, TENANT_ID);

        assertThat(errors).extracting(ImportRowError::message)
                .containsExactly("'contract.pdf' appears on rows 3 and 7 - each file needs a unique name");
    }

    @Test
    void validate_blankTitle_fallsBackToFilenameAndChecksItsLength() {
        String longName = "x".repeat(ImportTitles.MAX_LENGTH);
        assertThat(validate(item(2, longName + ".pdf", "key"), new RowValues("  ", Map.of()))).isEmpty();

        List<ImportRowError> errors = validate(item(2, longName + "y.pdf", "key"), new RowValues(null, Map.of()));
        assertThat(errors).extracting(ImportRowError::field, ImportRowError::code)
                .containsExactly(tuple("title", "TITLE_TOO_LONG"));
    }

    @Test
    void validate_titleTooLong_fails() {
        List<ImportRowError> errors = validate(item(2, "a.pdf", "key"),
                new RowValues("t".repeat(ImportTitles.MAX_LENGTH + 1), Map.of()));

        assertThat(errors).extracting(ImportRowError::code).containsExactly("TITLE_TOO_LONG");
        assertThat(errors.get(0).message()).isEqualTo("Title is 501 characters long - it can be at most 500");
    }

    @Test
    void validate_conversionError_suppressesMetadataErrorForTheSameField() {
        when(metadataValidationService.validate(eq(DOCUMENT_TYPE_ID), eq(VERSION), anyMap())).thenReturn(List.of(
                new ValidationError("contract_value", "MANDATORY_FIELD_MISSING",
                        "Field 'Contract Value' is required.", "error"),
                new ValidationError("start_date", "DATE_RANGE", "Start Date must be in 2026.", "error")));

        List<ImportRowError> errors = validate(item(2, "a.pdf", "key"),
                new RowValues(null, Map.of("contract_value", "12.5", "start_date", "2025-01-01")));

        assertThat(errors).containsExactly(
                ImportRowError.error("contract_value", "INVALID_NUMBER", "Contract Value must be a whole number"),
                ImportRowError.error("start_date", "DATE_RANGE", "Start Date must be in 2026."));
    }

    @Test
    void validate_warningOnly_isKeptButDoesNotFailTheRow() {
        when(metadataValidationService.validate(eq(DOCUMENT_TYPE_ID), eq(VERSION), anyMap())).thenReturn(List.of(
                new ValidationError("contract_value", "MAX_VALUE", "Unusually large contract.", "warning")));

        List<ImportRowError> errors = validate(item(2, "a.pdf", "key"),
                new RowValues(null, Map.of("contract_value", "99000000")));

        assertThat(errors).containsExactly(
                new ImportRowError("contract_value", "MAX_VALUE", "Unusually large contract.", "warning"));
        assertThat(ImportRowValidator.hasErrors(errors)).isFalse();
    }

    @Test
    void validate_userRef_caseMismatchOrInactiveUser_isUnknownUser() {
        when(userRepository.findByTenantIdAndEmailAndDeletedFalse(TENANT_ID, "locked@oak.lk"))
                .thenReturn(Optional.of(user(UUID.randomUUID(), User.UserStatus.locked)));
        when(userRepository.findByTenantIdAndEmailAndDeletedFalse(eq(TENANT_ID), eq("John@oak.lk")))
                .thenReturn(Optional.empty());

        assertThat(validate(item(2, "a.pdf", "key"), new RowValues(null, Map.of("owner", "locked@oak.lk"))))
                .extracting(ImportRowError::code).containsExactly("UNKNOWN_USER");
        assertThat(validate(item(2, "a.pdf", "key"), new RowValues(null, Map.of("owner", "John@oak.lk"))))
                .extracting(ImportRowError::message).containsExactly("Owner: no active user with email 'John@oak.lk'"
                        + " - check it matches the user's email exactly, including capital letters");
    }

    private List<ImportRowError> validate(ImportItem item, RowValues values) {
        return validator.validate(item, values, FIELDS, ImportRowValidator.duplicateRows(List.of(item)),
                DOCUMENT_TYPE_ID, VERSION, TENANT_ID);
    }

    private static ImportItem item(int rowNumber, String filename, String stagedObjectKey) {
        return ImportItem.builder()
                .importItemId(UUID.randomUUID())
                .tenantId(TENANT_ID)
                .rowNumber(rowNumber)
                .filenameReference(filename)
                .stagedObjectKey(stagedObjectKey)
                .rowValues("{}")
                .build();
    }

    private static User user(UUID userId, User.UserStatus status) {
        return User.builder().userId(userId).tenantId(TENANT_ID).status(status).build();
    }

    private static Map<String, ImportField> fields(ImportField... fields) {
        Map<String, ImportField> map = new LinkedHashMap<>();
        for (ImportField field : fields) {
            map.put(field.fieldKey(), field);
        }
        return map;
    }
}
