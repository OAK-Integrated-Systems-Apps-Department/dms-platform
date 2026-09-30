package com.oak.dms.importexport.validation;

import com.oak.dms.metadata.entity.DocumentTypeSchema;
import com.oak.dms.metadata.entity.DocumentTypeSchema.FieldMapping;
import com.oak.dms.metadata.entity.MetadataField;
import com.oak.dms.metadata.repository.DocumentTypeSchemaRepository;
import com.oak.dms.metadata.repository.MetadataFieldRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ImportSchemaFieldsTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID DOCUMENT_TYPE_ID = UUID.randomUUID();

    private final DocumentTypeSchemaRepository schemaRepository = mock(DocumentTypeSchemaRepository.class);
    private final MetadataFieldRepository fieldRepository = mock(MetadataFieldRepository.class);
    private final ImportSchemaFields schemaFields = new ImportSchemaFields(schemaRepository, fieldRepository);

    @Test
    void load_requestedVersion_mapsCatalogueFieldsWithVersionFlagsInDisplayOrder() {
        MetadataField amount = field("amount", "Amount", "decimal");
        MetadataField owner = field("owner", "Owner", "user_ref");
        MetadataField note = field("note", "Note", "text");
        MetadataField deleted = field("gone", "Gone", "text");
        when(schemaRepository.findByDocumentTypeIdAndTenantIdAndSchemaVersion(DOCUMENT_TYPE_ID, TENANT_ID, 2))
                .thenReturn(Optional.of(schema(2,
                        mapping(owner, false, false, 30),
                        mapping(amount, true, false, 10),
                        mapping(deleted, false, false, 5),
                        mapping(note, false, true, 20))));
        when(fieldRepository.findAllByFieldIdInAndTenantIdAndDeletedFalse(anyList(), eq(TENANT_ID)))
                .thenReturn(List.of(amount, owner, note));

        Map<String, ImportField> fields = schemaFields.load(TENANT_ID, DOCUMENT_TYPE_ID, 2);

        assertThat(fields.keySet()).containsExactly("amount", "note", "owner");
        assertThat(fields.get("amount"))
                .isEqualTo(new ImportField(amount.getFieldId(), "amount", "Amount", "decimal", true, false));
        assertThat(fields.get("note"))
                .isEqualTo(new ImportField(note.getFieldId(), "note", "Note", "text", false, true));
        assertThat(fields.get("owner"))
                .isEqualTo(new ImportField(owner.getFieldId(), "owner", "Owner", "user_ref", false, false));
    }

    @Test
    void load_missingVersion_throws() {
        when(schemaRepository.findByDocumentTypeIdAndTenantIdAndSchemaVersion(DOCUMENT_TYPE_ID, TENANT_ID, 9))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> schemaFields.load(TENANT_ID, DOCUMENT_TYPE_ID, 9))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No schema version 9");
    }

    private static DocumentTypeSchema schema(int version, FieldMapping... mappings) {
        return DocumentTypeSchema.builder()
                .schemaId(UUID.randomUUID())
                .tenantId(TENANT_ID)
                .documentTypeId(DOCUMENT_TYPE_ID)
                .schemaVersion(version)
                .fields(List.of(mappings))
                .build();
    }

    private static FieldMapping mapping(MetadataField field, boolean mandatory, boolean hidden, int displayOrder) {
        return new FieldMapping(UUID.randomUUID(), field.getFieldId(), mandatory, hidden, false, displayOrder, null,
                null);
    }

    private static MetadataField field(String key, String label, String dataType) {
        return MetadataField.builder()
                .fieldId(UUID.randomUUID())
                .tenantId(TENANT_ID)
                .fieldKey(key)
                .label(label)
                .dataType(dataType)
                .build();
    }
}
