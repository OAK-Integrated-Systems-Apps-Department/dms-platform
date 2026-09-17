package com.oak.dms.metadata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oak.dms.metadata.dto.CreateDocumentTypeRequest;
import com.oak.dms.metadata.dto.CreateMetadataFieldRequest;
import com.oak.dms.metadata.dto.DocumentTypeFieldMappingItem;
import com.oak.dms.metadata.dto.DocumentTypeResponse;
import com.oak.dms.metadata.dto.DocumentTypeSchemaResponse;
import com.oak.dms.metadata.dto.MetadataFieldResponse;
import com.oak.dms.metadata.dto.ReplaceDocumentTypeFieldsRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * document_types.icon, metadata_fields.is_indexed and
 * document_type_fields.is_readonly were removed end-to-end (V26) because
 * none of them were ever enforced. This asserts none of them leak back into
 * a serialized API response - the compiler already guarantees the DTO
 * records don't declare them, so this is specifically checking the actual
 * JSON shape the app's own ObjectMapper produces.
 */
class DroppedColumnsApiResponseIT extends AbstractMetadataIntegrationTest {

    @Autowired
    private DocumentTypeService documentTypeService;
    @Autowired
    private MetadataFieldService metadataFieldService;
    @Autowired
    private DocumentTypeFieldService documentTypeFieldService;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void documentTypeResponseHasNoIconField() throws Exception {
        DocumentTypeResponse response = documentTypeService.createDocumentType(
                new CreateDocumentTypeRequest("NO_ICON_DOC", "No Icon Doc", null, 0, false, false));
        String json = objectMapper.writeValueAsString(response);
        assertThat(json).doesNotContain("\"icon\"");
    }

    @Test
    void metadataFieldResponseHasNoIndexedField() throws Exception {
        MetadataFieldResponse response = metadataFieldService.createField(
                new CreateMetadataFieldRequest("no_indexed_field", "No Indexed Field", null, "text", null, false, true, false));
        String json = objectMapper.writeValueAsString(response);
        assertThat(json).doesNotContain("\"indexed\"");
    }

    @Test
    void documentTypeFieldResponseAndSchemaResponseHaveNoReadonlyField() throws Exception {
        UUID documentTypeId = documentTypeService.createDocumentType(
                new CreateDocumentTypeRequest("NO_READONLY_DOC", "No Readonly Doc", null, 0, false, false)).documentTypeId();
        UUID fieldId = metadataFieldService.createField(
                new CreateMetadataFieldRequest("no_readonly_field", "No Readonly Field", null, "text", null, false, true, false)).fieldId();

        var mappingResponse = documentTypeFieldService.replaceMapping(documentTypeId, new ReplaceDocumentTypeFieldsRequest(List.of(
                new DocumentTypeFieldMappingItem(fieldId, false, false, 10, null, null))));
        String mappingJson = objectMapper.writeValueAsString(mappingResponse);
        assertThat(mappingJson).doesNotContain("\"readonly\"");

        DocumentTypeSchemaResponse schemaResponse = documentTypeFieldService.getSchema(documentTypeId);
        String schemaJson = objectMapper.writeValueAsString(schemaResponse);
        assertThat(schemaJson).doesNotContain("\"readonly\"");
    }
}
