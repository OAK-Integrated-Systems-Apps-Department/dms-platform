package com.oak.dms.importexport.validation;

import com.oak.dms.importexport.entity.ImportItem;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class ImportRowValidatorTest {

    private static final ImportField CONTRACT_VALUE =
            new ImportField(UUID.randomUUID(), "contract_value", "Contract Value", "number", true, false, null);
    private static final ImportField OWNER =
            new ImportField(UUID.randomUUID(), "owner", "Owner", "user_ref", true, false, "gone@oak.lk");
    private static final ImportDefaults.Resolved NO_PROBLEMS =
            new ImportDefaults.Resolved(List.of(), List.of(), List.of());

    private final ImportRowValidator validator = new ImportRowValidator();

    @Test
    void validate_acceptedFileAndUsableDefaults_hasNoProblems() {
        assertThat(validator.validate(item("contract.pdf", "key"), NO_PROBLEMS, false)).isEmpty();
    }

    @Test
    void validate_noStagedFile_failsFileMissing() {
        List<ImportRowError> errors = validator.validate(item("contract.pdf", null), NO_PROBLEMS, false);

        assertThat(errors).containsExactly(
                ImportRowError.error("file", "FILE_MISSING", "The uploaded file could not be found"));
        assertThat(ImportRowValidator.hasErrors(errors)).isTrue();
    }

    @Test
    void validate_rejectedUpload_failsFileRejectedWithTheSavedReason() {
        ImportItem item = item("contract.pdf", null);
        item.setUploadRejectionCode("FILE_TYPE_NOT_ALLOWED");
        item.setUploadRejectionMessage("The file you uploaded ('contract.pdf') was rejected: The file's content"
                + " does not match its .pdf extension");

        assertThat(validator.validate(item, NO_PROBLEMS, false)).containsExactly(ImportRowError.error("file",
                "FILE_REJECTED", "The file you uploaded ('contract.pdf') was rejected: The file's content does not"
                        + " match its .pdf extension"));
    }

    @Test
    void validate_titleFromFilenameTooLong_failsTitleTooLong() {
        String name = "a".repeat(ImportTitles.MAX_LENGTH + 1) + ".pdf";

        List<ImportRowError> errors = validator.validate(item(name, "key"), NO_PROBLEMS, false);

        assertThat(errors).extracting(ImportRowError::field, ImportRowError::code)
                .containsExactly(tuple("title", "TITLE_TOO_LONG"));
    }

    @Test
    void validate_invalidDefault_isOnlyAWarning() {
        ImportRowError invalid = new ImportRowError("owner", "DEFAULT_VALUE_INVALID",
                "The default value for Owner couldn't be used (...) - it was left empty", ImportRowError.WARNING);

        List<ImportRowError> errors = validator.validate(item("contract.pdf", "key"),
                new ImportDefaults.Resolved(List.of(), List.of(invalid), List.of()), false);

        assertThat(errors).containsExactly(invalid);
        assertThat(ImportRowValidator.hasErrors(errors)).isFalse();
    }

    @Test
    void validate_mandatoryFieldsWithoutDefault_notEnforced_oneDeferredWarningListingThem() {
        List<ImportRowError> errors = validator.validate(item("contract.pdf", "key"),
                new ImportDefaults.Resolved(List.of(), List.of(), List.of(CONTRACT_VALUE, OWNER)), false);

        assertThat(errors).containsExactly(new ImportRowError(null, "MANDATORY_FIELD_DEFERRED",
                "Required fields left empty, to be filled in later: Contract Value and Owner",
                ImportRowError.WARNING));
        assertThat(ImportRowValidator.hasErrors(errors)).isFalse();
    }

    @Test
    void validate_mandatoryFieldsWithoutDefault_enforced_failsEachField() {
        List<ImportRowError> errors = validator.validate(item("contract.pdf", "key"),
                new ImportDefaults.Resolved(List.of(), List.of(), List.of(CONTRACT_VALUE, OWNER)), true);

        assertThat(errors).extracting(ImportRowError::field, ImportRowError::code, ImportRowError::severity)
                .containsExactly(
                        tuple("contract_value", "MANDATORY_FIELD_MISSING", ImportRowError.ERROR),
                        tuple("owner", "MANDATORY_FIELD_MISSING", ImportRowError.ERROR));
    }

    private static ImportItem item(String filename, String stagedObjectKey) {
        return ImportItem.builder()
                .importItemId(UUID.randomUUID())
                .tenantId(UUID.randomUUID())
                .rowNumber(1)
                .filenameReference(filename)
                .stagedObjectKey(stagedObjectKey)
                .rowValues("{}")
                .build();
    }
}
