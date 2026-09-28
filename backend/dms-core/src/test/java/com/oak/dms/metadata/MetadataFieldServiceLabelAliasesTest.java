package com.oak.dms.metadata;

import com.oak.dms.common.exception.FieldValidationException;
import com.oak.dms.metadata.cache.SchemaCacheService;
import com.oak.dms.metadata.dto.CreateMetadataFieldRequest;
import com.oak.dms.metadata.dto.MetadataFieldResponse;
import com.oak.dms.metadata.dto.UpdateMetadataFieldRequest;
import com.oak.dms.security.auth.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * How {@link MetadataFieldServiceImpl} carries a field's GLOBAL OCR label
 * hints: every response includes them, update treats a null list as "leave
 * unchanged" (not "clear"), and the list endpoint batch-loads them. Uses the
 * real MapStruct-generated mapper; everything else is mocked.
 */
@ExtendWith(MockitoExtension.class)
class MetadataFieldServiceLabelAliasesTest {

    @Mock
    private MetadataFieldRepository metadataFieldRepository;
    @Mock
    private DocumentTypeFieldRepository documentTypeFieldRepository;
    @Mock
    private SchemaCacheService schemaCacheService;
    @Mock
    private MetadataFieldLabelAliasService labelAliasService;

    private MetadataFieldServiceImpl service;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID fieldId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        TenantContext.set(tenantId, UUID.randomUUID());
        service = new MetadataFieldServiceImpl(metadataFieldRepository, documentTypeFieldRepository,
                new MetadataFieldMapperImpl(), schemaCacheService, labelAliasService);
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    private MetadataField existingField() {
        MetadataField field = existingFieldNotSaved();
        when(metadataFieldRepository.save(field)).thenReturn(field);
        return field;
    }

    private MetadataField existingFieldNotSaved() {
        MetadataField field = MetadataField.builder()
                .fieldId(fieldId).tenantId(tenantId).fieldKey("po_number").label("PO Number").dataType("text")
                .build();
        when(metadataFieldRepository.findByFieldIdAndTenantIdAndDeletedFalse(fieldId, tenantId))
                .thenReturn(Optional.of(field));
        return field;
    }

    private static UpdateMetadataFieldRequest update(List<String> labelAliases) {
        return new UpdateMetadataFieldRequest("PO Number", null, null, true, true, labelAliases);
    }

    @Test
    void updateWithNullLabelAliasesLeavesTheStoredHintsUnchanged() {
        existingField();
        when(labelAliasService.listGlobalAliases(tenantId, fieldId)).thenReturn(List.of("PO Number", "PO #"));

        MetadataFieldResponse response = service.updateField(fieldId, update(null));

        assertThat(response.labelAliases()).containsExactly("PO Number", "PO #");
        verify(labelAliasService, never()).replaceGlobalAliases(any(), any(), anyList());
    }

    @Test
    void updateWithAnEmptyListClearsTheHints() {
        existingField();
        when(labelAliasService.replaceGlobalAliases(tenantId, fieldId, List.of())).thenReturn(List.of());

        MetadataFieldResponse response = service.updateField(fieldId, update(List.of()));

        assertThat(response.labelAliases()).isEmpty();
        verify(labelAliasService).replaceGlobalAliases(tenantId, fieldId, List.of());
    }

    @Test
    void updateWithAListReplacesTheHintsWithItsNormalizedForm() {
        existingField();
        when(labelAliasService.replaceGlobalAliases(tenantId, fieldId, List.of("PO Number", "Order No")))
                .thenReturn(List.of("PO Number", "Order No"));

        MetadataFieldResponse response = service.updateField(fieldId, update(List.of("  PO   Number", "Order No")));

        assertThat(response.labelAliases()).containsExactly("PO Number", "Order No");
    }

    @Test
    void updateWithAnInvalidListFailsBeforeSavingTheField() {
        existingFieldNotSaved();

        assertThatThrownBy(() -> service.updateField(fieldId, update(List.of("x"))))
                .isInstanceOf(FieldValidationException.class);

        verify(metadataFieldRepository, never()).save(any());
        verify(labelAliasService, never()).replaceGlobalAliases(any(), any(), anyList());
    }

    @Test
    void createWithoutLabelAliasesStoresNoneAndReturnsAnEmptyList() {
        when(metadataFieldRepository.save(any(MetadataField.class))).thenAnswer(invocation -> {
            MetadataField field = invocation.getArgument(0);
            field.setFieldId(fieldId);
            return field;
        });

        MetadataFieldResponse response = service.createField(new CreateMetadataFieldRequest(
                "po_number", "PO Number", null, "text", null, true, true, null));

        assertThat(response.labelAliases()).isEmpty();
        verify(labelAliasService, never()).replaceGlobalAliases(any(), any(), anyList());
    }

    @Test
    void createWithLabelAliasesStoresThemAsGlobalHints() {
        when(metadataFieldRepository.save(any(MetadataField.class))).thenAnswer(invocation -> {
            MetadataField field = invocation.getArgument(0);
            field.setFieldId(fieldId);
            return field;
        });
        when(labelAliasService.replaceGlobalAliases(tenantId, fieldId, List.of("PO Number")))
                .thenReturn(List.of("PO Number"));

        MetadataFieldResponse response = service.createField(new CreateMetadataFieldRequest(
                "po_number", "PO Number", null, "text", null, true, true, List.of(" PO Number ", "po number")));

        assertThat(response.labelAliases()).containsExactly("PO Number");
    }

    @Test
    void listBatchLoadsEveryFieldsHintsInOneCall() {
        UUID otherFieldId = UUID.randomUUID();
        MetadataField first = MetadataField.builder().fieldId(fieldId).tenantId(tenantId)
                .fieldKey("po_number").label("PO Number").dataType("text").build();
        MetadataField second = MetadataField.builder().fieldId(otherFieldId).tenantId(tenantId)
                .fieldKey("vendor").label("Vendor").dataType("text").build();
        when(metadataFieldRepository.search(eq(tenantId), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(first, second), PageRequest.of(0, 20), 2));
        when(labelAliasService.listGlobalAliasesByField(tenantId, List.of(fieldId, otherFieldId)))
                .thenReturn(Map.of(fieldId, List.of("PO Number"), otherFieldId, List.of()));

        List<MetadataFieldResponse> responses = service.listFields(null, null, null, PageRequest.of(0, 20)).getContent();

        assertThat(responses).extracting(MetadataFieldResponse::labelAliases)
                .containsExactly(List.of("PO Number"), List.of());
        verify(labelAliasService, never()).listGlobalAliases(any(), any());
    }
}
