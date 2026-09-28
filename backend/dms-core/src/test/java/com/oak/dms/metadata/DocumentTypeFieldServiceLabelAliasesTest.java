package com.oak.dms.metadata;

import com.oak.dms.metadata.cache.SchemaCacheService;
import com.oak.dms.metadata.dto.DocumentTypeFieldMappingItem;
import com.oak.dms.metadata.dto.DocumentTypeFieldResponse;
import com.oak.dms.metadata.dto.ReplaceDocumentTypeFieldsRequest;
import com.oak.dms.security.auth.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * How a publish ({@link DocumentTypeFieldServiceImpl#replaceMapping}) treats
 * type-scoped OCR label hints: the fields it keeps carry theirs over
 * untouched, and the fields it drops lose theirs - ocr-worker would
 * otherwise keep using a dropped field's hints as stop labels on every
 * document of the type, while the label-aliases endpoints 404 on an
 * unmapped field and the Fields tab lists mapped rows only, leaving the
 * admin no way to see or clear them. Repositories and the hint service are
 * mocked; pattern as MetadataFieldServiceLabelAliasesTest.
 */
@ExtendWith(MockitoExtension.class)
class DocumentTypeFieldServiceLabelAliasesTest {

    @Mock
    private DocumentTypeRepository documentTypeRepository;
    @Mock
    private DocumentTypeFieldRepository documentTypeFieldRepository;
    @Mock
    private MetadataFieldRepository metadataFieldRepository;
    @Mock
    private MetadataFieldOptionRepository metadataFieldOptionRepository;
    @Mock
    private MetadataFieldValidationRepository metadataFieldValidationRepository;
    @Mock
    private SchemaCacheService schemaCacheService;
    @Mock
    private MetadataFieldLabelAliasService labelAliasService;

    private DocumentTypeFieldServiceImpl service;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID documentTypeId = UUID.randomUUID();
    private final UUID keptFieldId = UUID.randomUUID();
    private final UUID droppedFieldId = UUID.randomUUID();
    private final UUID addedFieldId = UUID.randomUUID();
    private static final int CURRENT_VERSION = 4;

    @BeforeEach
    void setUp() {
        TenantContext.set(tenantId, UUID.randomUUID());
        // DocumentTypeFieldMapper's only method is a default one, so an
        // anonymous instance is the real mapping logic.
        service = new DocumentTypeFieldServiceImpl(documentTypeRepository, documentTypeFieldRepository,
                metadataFieldRepository, metadataFieldOptionRepository, metadataFieldValidationRepository,
                new DocumentTypeFieldMapper() { }, schemaCacheService, labelAliasService);
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    private void givenCurrentVersionMaps(UUID... fieldIds) {
        when(documentTypeRepository.lockByDocumentTypeIdAndTenantId(documentTypeId, tenantId))
                .thenReturn(Optional.of(DocumentType.builder()
                        .documentTypeId(documentTypeId).tenantId(tenantId)
                        .currentSchemaVersion(CURRENT_VERSION).build()));
        List<DocumentTypeField> current = Arrays.stream(fieldIds)
                .map(fieldId -> DocumentTypeField.builder()
                        .documentTypeFieldId(UUID.randomUUID()).tenantId(tenantId).documentTypeId(documentTypeId)
                        .fieldId(fieldId).schemaVersion(CURRENT_VERSION).build())
                .toList();
        when(documentTypeFieldRepository.findByDocumentTypeAndVersion(documentTypeId, tenantId, CURRENT_VERSION))
                .thenReturn(current);
        when(documentTypeFieldRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private void givenCatalogFields(UUID... fieldIds) {
        List<MetadataField> fields = Arrays.stream(fieldIds)
                .map(fieldId -> MetadataField.builder()
                        .fieldId(fieldId).tenantId(tenantId).fieldKey("key_" + fieldId).label("Label")
                        .dataType("text").build())
                .toList();
        when(metadataFieldRepository.findAllByFieldIdInAndTenantIdAndDeletedFalse(anyList(), eq(tenantId)))
                .thenReturn(fields);
    }

    private static DocumentTypeFieldMappingItem item(UUID fieldId, int displayOrder) {
        return new DocumentTypeFieldMappingItem(fieldId, false, false, displayOrder, null, null);
    }

    @Test
    void aPublishDeletesTheTypeScopedHintsOfEveryFieldItDropsAndKeepsTheRest() {
        givenCurrentVersionMaps(keptFieldId, droppedFieldId);
        givenCatalogFields(keptFieldId, addedFieldId);
        when(labelAliasService.listTypeScopedAliasesByField(tenantId, documentTypeId))
                .thenReturn(Map.of(keptFieldId, List.of("PO Number", "Number")));

        List<DocumentTypeFieldResponse> response = service.replaceMapping(documentTypeId,
                new ReplaceDocumentTypeFieldsRequest(List.of(item(keptFieldId, 10), item(addedFieldId, 20))));

        // Everything that is NOT in the new version goes - the dropped field
        // here - under the document type's row lock, the same lock the hint
        // PUT takes, so a concurrent hint save can't slip in between.
        InOrder order = inOrder(documentTypeRepository, labelAliasService);
        order.verify(documentTypeRepository).lockByDocumentTypeIdAndTenantId(documentTypeId, tenantId);
        order.verify(labelAliasService).deleteTypeScopedAliasesOfUnmappedFields(
                tenantId, documentTypeId, List.of(keptFieldId, addedFieldId));

        // The kept field's hints carry over into the new version untouched.
        assertThat(response).extracting(DocumentTypeFieldResponse::fieldId).containsExactly(keptFieldId, addedFieldId);
        assertThat(response.get(0).labelAliases()).containsExactly("PO Number", "Number");
        assertThat(response.get(1).labelAliases()).isEmpty();
        verify(labelAliasService, never()).replaceGlobalAliases(any(), any(), any());
    }

    @Test
    void aPublishThatMapsNoFieldsClearsEveryTypeScopedHintOfTheType() {
        givenCurrentVersionMaps(droppedFieldId);
        givenCatalogFields();

        assertThat(service.replaceMapping(documentTypeId, new ReplaceDocumentTypeFieldsRequest(List.of()))).isEmpty();

        verify(labelAliasService).deleteTypeScopedAliasesOfUnmappedFields(tenantId, documentTypeId, List.of());
    }
}
